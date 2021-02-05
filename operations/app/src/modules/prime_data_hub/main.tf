locals {
    location = "eastus"
}

module "storage" {
    source = "../storage"
    environment = var.environment
    resource_group = var.resource_group
    name = "${var.resource_prefix}storageaccount"
    location = local.location
    subnet_ids = [module.network.public_subnet_id,
                  module.network.container_subnet_id,
                  module.network.private_subnet_id]
}

module "network" {
    source = "../network"
    environment = var.environment
    resource_group = var.resource_group
    resource_prefix = var.resource_prefix
    location = local.location
}

module "container_registry" {
    source = "../container_registry"
    environment = var.environment
    resource_group = var.resource_group
    name = "${var.resource_prefix}containerregistry"
    location = local.location
    public_subnet_id = module.network.public_subnet_id
}

module "function_app" {
    source = "../function_app"
    environment = var.environment
    resource_group = var.resource_group
    resource_prefix = var.resource_prefix
    location = local.location
    storage_account_name = module.storage.storage_account_name
    storage_account_key = module.storage.storage_account_key
    public_subnet_id = module.network.public_subnet_id
}