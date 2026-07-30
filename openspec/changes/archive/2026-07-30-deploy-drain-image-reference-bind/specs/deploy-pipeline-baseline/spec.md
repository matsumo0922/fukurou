## ADDED Requirements

### Requirement: Compose invocations are preceded by an image reference bind

Every `docker compose` invocation the executor performs against the production compose file MUST occur after the candidate's immutable image reference has been exported as `FUKUROU_IMAGE_REFERENCE`, because the compose file declares that variable as required and interpolates it on every subcommand — not only on `up`. The exported value MUST be `<image repository>@<candidate digest>` derived from the paused-state marker's expected digest, so a bind that runs earlier does not weaken the digest pinning verified after cutover.

#### Scenario: Forced stop during drain interpolates the compose file successfully

- **WHEN** in-flight launches do not reach zero within the natural drain deadline and the executor falls back to stopping the application with `docker compose stop`
- **THEN** `FUKUROU_IMAGE_REFERENCE` is already exported at that point, so compose file interpolation succeeds and the stop is not rejected with a missing-variable error

#### Scenario: Forced stop keeps the established drain order

- **WHEN** the executor takes the forced stop path during drain
- **THEN** it still stops the application, proves the application PID is zero, interrupts remaining active launches, and only then waits for the drain to complete — the added bind does not reorder, skip, or replace any of these steps

#### Scenario: Bound reference matches the candidate digest at cutover

- **WHEN** the executor reaches compose cutover after any drain path, forced or natural
- **THEN** the image reference passed to compose equals `<image repository>@<expected digest>` recorded in the paused-state marker, and the post-cutover running-digest verification compares against that same value
