# RDS for MySQL 8.4, the same version the project runs locally and in tests. The smallest instance, one zone:
# a demo, not production. Reachable only from the wms-core Lambda's security group; no public address.

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

resource "aws_security_group" "wms_core" {
  name        = "${local.name}-wms-core"
  description = "wms-core Lambda: outbound to the database only"
  vpc_id      = data.aws_vpc.default.id

  egress {
    description = "MySQL"
    from_port   = 3306
    to_port     = 3306
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.default.cidr_block]
  }
}

resource "aws_security_group" "database" {
  name        = "${local.name}-database"
  description = "RDS: MySQL from the wms-core Lambda only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "MySQL from wms-core"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.wms_core.id]
  }
}

resource "aws_db_subnet_group" "wms" {
  name       = local.name
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_db_parameter_group" "wms" {
  name   = "${local.name}-mysql84"
  family = "mysql8.4"

  # Lets the application user create the ledger's append-only triggers with binary logging on (ADR 0003).
  # Same flag as deploy/compose and the Testcontainers setup.
  parameter {
    name  = "log_bin_trust_function_creators"
    value = "1"
  }

  # Timestamps are UTC end to end (ADR 0018).
  parameter {
    name  = "time_zone"
    value = "UTC"
  }
}

resource "random_password" "database" {
  length  = 32
  special = false
}

resource "aws_db_instance" "wms" {
  identifier     = local.name
  engine         = "mysql"
  engine_version = "8.4.11"
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "wms"
  username = "wms"
  password = random_password.database.result

  db_subnet_group_name   = aws_db_subnet_group.wms.name
  vpc_security_group_ids = [aws_security_group.database.id]
  parameter_group_name   = aws_db_parameter_group.wms.name
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period = 1
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true
}
