# Current Available Logs in Azure

This is a living document of all logs available to us.

| Log Type                       | Type                                    | PII/PHI | Location                     | In Splunk | Required in Splunk for ATO | Notes                                                   |
| :----------------------------- | :-------------------------------------- | :------ | :--------------------------- | :-------- | :------------------------- | :------------------------------------------------------ |
| Azure Activity Log             | Access and environment changes in Azure | No      | Subscription > Activity Logs | Yes       | Yes                        |                                                         |
| Functions: pdh*-functionapp    | Application logs                        | No      | In Application Insights      | No        | ?                          |                                                         |
| Database: pdh*-pgsql           | Database errors, access errors, etc     | No      | Resource > Logs              | No        | ?                          | Could be other types of logs to capture like slow query |
| Front Door                     | Network requests                        | No      | Disabled                     | No        | ?                          | Enable these logs                                       |
| WAF                            | Firewall denials                        | No      | No WAF enabled, so no logs   | No        | ?                          | Enable these logs                                       |
| Storage Account: primedatahub* | Access errors, etc                      | No      | Resource > Logs              | No        | ?                          |                                                         |
| Okta                           | Access                                  | No      | In Okta                      | No        | No                         | We'd like to get this in Splunk                         |
| SendGrid (Future)              | Email activity                          | TBD     | In SendGrid                  | No        | No                         | We'd like to get this in Splunk                         |
| Akamai (Future)                | Access                                  | No      | In Akamai                    | No        | No                         |                                                         |