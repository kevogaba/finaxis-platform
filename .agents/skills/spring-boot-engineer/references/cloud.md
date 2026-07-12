# Cloud Native and Operations

Use Spring Boot Actuator, Micrometer/OpenTelemetry, Sentry by environment-specific configuration, RabbitMQ for selected externalized events, Redis for distributed rate limiting/session infrastructure, and JobRunr for durable jobs.

## Project Choices

- Spring Web MVC only.
- Lettuce-based Redis clients for standalone, Sentinel, and Cluster options.
- Bucket4j + Redis for production rate limiting.
- Spring Modulith application events and Namastack Outbox for transactional event externalization.
- RabbitMQ listeners stay thin: deserialize, validate, delegate, handle idempotency, ack/nack by outcome.
- JobRunr handles durable background work; it is not the primary outbox engine.
