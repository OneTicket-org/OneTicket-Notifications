# OneTicket Notifications

Notifications is the Kotlin and Spring Boot microservice responsible for sending order confirmation, ticket delivery, and refund confirmation emails. It owns email delivery tracking data. Events that trigger emails are handled asynchronously through the message broker; sending a notification must not block or invalidate an order.

## Requirements

- JDK 25
- Docker Compose and the central `OneTicket-Main` repository for PostgreSQL and RabbitMQ

## Configuration

Environment variables override the local defaults where one is defined.

| Environment variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `SERVER_PORT` | No | `8083` | HTTP port used by the Notifications application. |
| `SPRING_DATASOURCE_URL` | No | `jdbc:postgresql://localhost:5433/notifications_db` | JDBC URL of the Notifications database. Use the Compose hostname and container port when running in Docker. |
| `SPRING_DATASOURCE_USERNAME` | No | `notifications` | PostgreSQL account used by Notifications. |
| `SPRING_DATASOURCE_PASSWORD` | Yes | None | Password for the Notifications PostgreSQL account. Do not commit this value. |
| `SPRING_RABBITMQ_HOST` | No | `localhost` | RabbitMQ host. Use `rabbitmq` from inside Compose. |
| `SPRING_RABBITMQ_PORT` | No | `5672` | RabbitMQ AMQP port. |
| `SPRING_RABBITMQ_USERNAME` | No | `guest` | RabbitMQ account used by Notifications; Compose supplies the project's configured account. |
| `SPRING_RABBITMQ_PASSWORD` | No | `guest` | Password for the RabbitMQ account; Compose supplies it from the central `.env` file. |

The central Compose project provisions a dedicated `notifications_db` database and `notifications` PostgreSQL account in its PostgreSQL container. It also passes the RabbitMQ credentials from the central repository's untracked `.env` file. Spring Boot does not load `.env` files by itself: Compose passes values to the container, while a local run needs the relevant variables set in the IDE or shell.

## Run locally

Start PostgreSQL and RabbitMQ from `OneTicket-Main`, then set the database password and RabbitMQ credentials in the IntelliJ run configuration or PowerShell session. For example:

```powershell
$env:SPRING_DATASOURCE_PASSWORD = "<value of NOTIFICATIONS_DB_PASSWORD from OneTicket-Main/.env>"
$env:SPRING_RABBITMQ_USERNAME = "<value of RABBITMQ_USER from OneTicket-Main/.env>"
$env:SPRING_RABBITMQ_PASSWORD = "<value of RABBITMQ_PASSWORD from OneTicket-Main/.env>"
./gradlew.bat bootRun
```

The service listens on `http://localhost:8083`. The readiness health endpoint is `http://localhost:8083/actuator/health/readiness`.

## Run with Docker Compose

From the central `OneTicket-Main` repository, build and start the service with the local override while the central submodule does not yet contain the Notifications Dockerfile:

```powershell
docker compose -f docker-compose.yaml -f docker-compose.local.yaml up -d --build notifications
```

The override builds from the sibling `OneTicket-Notifications` checkout. In the Compose network, Notifications connects to PostgreSQL at `postgres:5432/notifications_db` and RabbitMQ at `rabbitmq:5672`; host port `8083` is published for local access.

To follow the service logs:

```powershell
docker compose -f docker-compose.yaml -f docker-compose.local.yaml logs -f notifications
```

## Integrations planned for service implementation

The central Milestone 2 design describes consuming order-paid, tickets-issued, and refund-succeeded messages; resolving customer email addresses through the Keycloak Admin API using a restricted service account; and reading ticket PDFs from object storage. Those business integrations, SMTP configuration, retry policy, and message contracts are not configured here: the design leaves details such as message contracts, mail server, and retry values open. Add their dependencies and settings when those contracts and choices are implemented.
