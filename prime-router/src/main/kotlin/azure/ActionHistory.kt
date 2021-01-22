package gov.cdc.prime.router.azure

import com.fasterxml.jackson.core.JsonFactory
import com.microsoft.azure.functions.ExecutionContext
import com.microsoft.azure.functions.HttpRequestMessage
import com.microsoft.azure.functions.HttpResponseMessage
import gov.cdc.prime.router.ClientSource
import gov.cdc.prime.router.OrganizationService
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.azure.db.Tables.ACTION
import gov.cdc.prime.router.azure.db.Tables.REPORT_FILE
import gov.cdc.prime.router.azure.db.Tables.REPORT_LINEAGE
import gov.cdc.prime.router.azure.db.enums.TaskAction
import gov.cdc.prime.router.azure.db.tables.pojos.Action
import gov.cdc.prime.router.azure.db.tables.pojos.ReportFile
import gov.cdc.prime.router.azure.db.tables.pojos.ReportLineage
import org.jooq.Configuration
import org.jooq.impl.DSL
import java.io.ByteArrayOutputStream
import java.lang.IllegalArgumentException
import java.util.UUID

/**
 * This is a container class that holds information to be stored, about a single action,
 * as well as the reports that went into that Action, and were created by that Action.
 *
 * The idea is that, as an action progresses, call various track*(...) methods here to add additional information to this container.
 *
 * Then when the action is done, call saveToDb(...) to put all the tracked information into the database.
 *
 */
class ActionHistory {

    private var context: ExecutionContext?

    /**
     * Throughtout, using generated mutable jooq POJOs purely as a convenience to storage information
     *
     */

    var action = Action()

    /*
     * Reports that are inputs to this action, from previous steps (already in database)
     */
    var reportsIn = mutableMapOf<UUID, ReportFile>()

    /*
     * Reports that are inputs to this action, from external source (not already in database)
     * Note that this should be able to handle multiple submitted reports in one action.
     */
    var reportsReceived = mutableMapOf<UUID, ReportFile>()

    /*
     * New reports generated by this action.  (also not yet in database)
     */
    var reportsOut = mutableMapOf<UUID, ReportFile>()

    /**
     *
     * Collection of all the parent-child report relationships created by this action.
     *
     * Note:  There is a strong OO argument that this list should be broken out into each individual child Report.kt.
     * (That is, every report should know its own parents!)
     * However, its here because there are Functions that do not create Report.kt objects.  For example, Send.
     * In addition, in-memory, reports get copied many times, with lots of parent-child relationships
     * that are error-prone to track.  Hiding the lineage data here helps ensure correctness and hide complexity.
     *
     * todo Note that this does not work for command line.   Is that a problem?
     * todo this is redundant with `Report.sources`.   Merge these together; eliminate one of them.
     *
     */
    private val reportLineages = mutableListOf<ReportLineage>()

    constructor(actionStr: String, context: ExecutionContext? = null) {
        try {
            action.actionName = TaskAction.valueOf(actionStr)
            this.context = context
        } catch (e: IllegalArgumentException) {
            error("Unknown action $actionStr")
        }
    }

    fun trackActionParams(request: HttpRequestMessage<String?>) {
        val factory = JsonFactory()
        val outStream = ByteArrayOutputStream()
        factory.createGenerator(outStream).use {
            it.useDefaultPrettyPrinter()
            it.writeStartObject()
            it.writeStringField("method", request.httpMethod.toString())
            it.writeObjectFieldStart("Headers")
            // remove secrets
            request.headers.filter { !it.key.contains("key") }.forEach { (key, value) ->
                it.writeStringField(key, value)
            }
            it.writeEndObject()
            it.writeObjectFieldStart("QueryParameters")
            // remove secrets
            request.queryParameters.filter { !it.key.contains("code") }.forEach { (key, value) ->
                it.writeStringField(key, value)
            }
            it.writeEndObject()
            it.writeEndObject()
        }
        // truncate if needed
        action.actionParams = outStream.toString().chunked(512)[0]
    }

    fun trackActionResult(actionResult: String) {
        // TODO should be able to get this max size from the jooq-generated code.
        // chop the string down to 2048 if needed.
        action.actionResult = actionResult.chunked(2048)[0]
    }

    fun trackActionResult(httpResponseMessage: HttpResponseMessage) {
        trackActionResult(httpResponseMessage.status.toString() + ":\n" + httpResponseMessage.body.toString())
    }

    fun trackExternalIncomingReport(incomingReport: ReportFunction.ValidatedRequest) {
        val report = incomingReport.report ?: error("No report to track!")
        if (reportsIn.containsKey(report.id)) {
            error("Bug:  attempt to track history of a report ($report.id) we've already associated with this action")
        }

        val reportFile = ReportFile()
        reportFile.reportId = report.id
        reportFile.nextAction = TaskAction.none
        if (report.sources.size != 1) {
            error("An external incoming report should have only one source.   Report ${report.id} had ${report.sources.size} sources")
        }
        val source = (report.sources[0] as ClientSource)
        reportFile.sendingOrg = source.organization
        reportFile.sendingOrgClient = source.client
        reportFile.schemaName = report.schema.name
        reportFile.schemaTopic = report.schema.topic
        reportFile.bodyUrl = report.bodyURL
        reportFile.bodyFormat = report.getBodyFormat().toString()
        reportFile.itemCount = report.itemCount
        reportsReceived[reportFile.reportId] = reportFile
    }

    /* Table structure here for reference during development. Might be out of date.
        public ReportFile(
            UUID           reportId,
            Integer        actionId,
            TaskAction     nextAction,
            OffsetDateTime nextActionAt,
            String         sendingOrg,
            String         sendingOrgClient,
            String         receivingOrg,
            String         receivingOrgSvc,
            String         transmissionParams,
            String         transmissionResult,
            String         schemaName,
            String         schemaTopic,
            String         bodyUrl,
            String         bodyFormat,
            byte[]         blobDigest,
            Integer        itemCount,
            OffsetDateTime wipedAt,
            OffsetDateTime createdAt
        */
    fun trackCreatedReport(
        event: Event,
        report: Report,
        service: OrganizationService,
        validatedRequest: ReportFunction.ValidatedRequest
    ) {
        if (reportsIn.containsKey(report.id)) {
            error("Bug:  attempt to track history of a report ($report.id) we've already associated with this action")
        }

        val reportFile = ReportFile()
        reportFile.reportId = report.id
        reportFile.nextAction = event.action.toTaskAction()
        reportFile.nextActionAt = event.at
        reportFile.receivingOrg = service.organization.name
        reportFile.receivingOrgSvc = service.name
        reportFile.schemaName = report.schema.name
        reportFile.schemaTopic = report.schema.topic
        reportFile.bodyUrl = report.bodyURL
        reportFile.bodyFormat = report.getBodyFormat().toString()
        reportFile.itemCount = report.itemCount
        reportsOut[reportFile.reportId] = reportFile
    }

    /**
     * Save the history about this action and related reports
     */
    fun saveToDb(db: DatabaseAccess, txn: Configuration? = null) {
        // prep work.  Right now we are redundantly storing the params and results in both the action and the report.
        // I'm not happy with this.   Get rid of it.
        if (action.actionName.equals(TaskAction.receive)) {
            reportsReceived.values.forEach {
                it.transmissionParams = action.actionParams
                it.transmissionResult = action.actionResult
            }
        }
        if (action.actionName.equals(TaskAction.send)) {
            reportsOut.values.forEach {
                it.transmissionParams = action.actionParams
                it.transmissionResult = action.actionResult
            }
        }
        if (txn != null) {
            insertAll(txn)
        } else {
            db.transact { innerTxn -> insertAll(innerTxn) }
        }
    }

    private fun insertAll(txn: Configuration) {
        action.actionId = insertAction(txn)
        reportsReceived.values.forEach { it.actionId = action.actionId }
        reportsOut.values.forEach { it.actionId = action.actionId }
        insertReports(txn)
        generateReportLineages()
        insertLineages(txn)
    }

    /**
     * Returns the action_id PK of the newly inserted ACTION.
     */
    private fun insertAction(txn: Configuration): Int {
        val record =
            DSL.using(txn)
                .insertInto(
                    ACTION,
                    ACTION.ACTION_NAME,
                    ACTION.ACTION_PARAMS,
                    ACTION.ACTION_RESULT,
                ).values(
                    action.actionName,
                    action.actionParams,
                    action.actionResult
                ).returningResult(ACTION.ACTION_ID).fetchOne()
        if (record != null) {
            context?.logger?.info("Saved to ACTION: ${action.actionName}, id=${record.getValue(ACTION.ACTION_ID)}")
        } else {
            error("Failed to insert action ${action.actionName} into database")
        }
        return record.getValue(ACTION.ACTION_ID)
    }

    private fun insertReports(txn: Configuration) {
        reportsReceived.values.forEach {
            insertReportFile(it, txn)
        }
        reportsOut.values.forEach {
            insertReportFile(it, txn)
        }
    }

    /**
     * TODO figure out how to just pass the ReportFile POJO directly here.
     */
    private fun insertReportFile(reportFile: ReportFile, txn: Configuration) {
        DSL.using(txn)
            .insertInto(
                REPORT_FILE,
                REPORT_FILE.REPORT_ID,
                REPORT_FILE.ACTION_ID,
                REPORT_FILE.NEXT_ACTION,
                REPORT_FILE.NEXT_ACTION_AT,
                REPORT_FILE.SENDING_ORG,
                REPORT_FILE.SENDING_ORG_CLIENT,
                REPORT_FILE.RECEIVING_ORG,
                REPORT_FILE.RECEIVING_ORG_SVC,
                REPORT_FILE.TRANSMISSION_PARAMS,
                REPORT_FILE.TRANSMISSION_RESULT,
                REPORT_FILE.SCHEMA_NAME,
                REPORT_FILE.SCHEMA_TOPIC,
                REPORT_FILE.BODY_URL,
                REPORT_FILE.BODY_FORMAT,
                REPORT_FILE.BLOB_DIGEST,
                REPORT_FILE.ITEM_COUNT,
                REPORT_FILE.WIPED_AT,
            ).values(
                reportFile.reportId,
                reportFile.actionId,
                reportFile.nextAction,
                reportFile.nextActionAt,
                reportFile.sendingOrg,
                reportFile.sendingOrgClient,
                reportFile.receivingOrg,
                reportFile.receivingOrgSvc,
                reportFile.transmissionParams,
                reportFile.transmissionResult,
                reportFile.schemaName,
                reportFile.schemaTopic,
                reportFile.bodyUrl,
                reportFile.bodyFormat,
                reportFile.blobDigest,
                reportFile.itemCount,
                reportFile.wipedAt,
            ).execute()
        val fromInfo =
            if (!reportFile.sendingOrg.isNullOrEmpty()) "(from ${reportFile.sendingOrg}.${reportFile.sendingOrgClient})" else ""
        val toInfo =
            if (!reportFile.receivingOrg.isNullOrEmpty()) "(to ${reportFile.receivingOrg}.${reportFile.receivingOrgSvc})" else ""
        context?.logger?.info("Saved to REPORT_FILE: ${reportFile.reportId} $fromInfo $toInfo")
    }

    /**
     * Assume that every parent report played a hand in creating every child report.
     * This is a lovely simplification, because it means that the functions don't have to
     * worry about lineage tracking at all.
     */
    private fun generateReportLineages() {
        reportsIn.keys.forEach { parentId ->
            reportsOut.keys.forEach { childId ->
                reportLineages.add(ReportLineage(null, parentId, childId, null))
            }
        }
        reportsReceived.keys.forEach { parentId ->
            reportsOut.keys.forEach { childId ->
                reportLineages.add(ReportLineage(null, parentId, childId, null))
            }
        }
    }

    private fun insertLineages(txn: Configuration) {
        reportLineages.forEach {
            insertReportLineage(it, txn)
        }
    }

    private fun insertReportLineage(lineage: ReportLineage, txn: Configuration) {
        DSL.using(txn)
            .insertInto(
                REPORT_LINEAGE,
                REPORT_LINEAGE.PARENT_REPORT_ID,
                REPORT_LINEAGE.CHILD_REPORT_ID,
            ).values(
                lineage.parentReportId,
                lineage.childReportId,
            ).execute()
        context?.logger?.info("Report ${lineage.parentReportId} is a parent of child report ${lineage.childReportId}")
    }
}