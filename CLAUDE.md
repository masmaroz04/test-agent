# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build (skip tests)
./mvnw -B clean package -DskipTests

# Run tests
./mvnw -B clean test

# Run a single test class
./mvnw -B test -Dtest=UserServiceTest

# Run the app locally
./mvnw spring-boot:run

# Build Docker image
docker build -t users-api .
```

## Architecture

Spring Boot 4 REST API (Java 17) with a single `user` domain package. No database — data is seeded in-memory via a static `Map` in `UserService`.

**Request flow:**

```
Client → UserController (/api/v1/users) → UserService → in-memory Map<Long, User>
```

**Error handling:** `ApiExceptionHandler` (`@RestControllerAdvice`) catches `UserNotFoundException` and returns an RFC 9457 `ProblemDetail` with HTTP 404.

**Health/readiness:** Spring Actuator is included; the k8s `deployment.yaml` probes `/actuator/health/readiness` and `/actuator/health/liveness`.

## CI/CD and Kubernetes

The `Jenkinsfile` pipeline runs on an agent labeled `linux && docker` and performs: test → package → Azure login (service principal) → Docker build/push to ACR → `kubectl apply` to AKS.

K8s YAML files under `k8s/` use `__PLACEHOLDER__` tokens that Jenkins replaces with `sed` before applying:
- `__NAMESPACE__` → `K8S_NAMESPACE` param (default: `users-api`)
- `__IMAGE__` → `ACR_LOGIN_SERVER/IMAGE_REPO:BUILD_NUMBER-shortSHA`

The pipeline image is built for `linux/arm64` (AKS node architecture). Change the `--platform` flag in the `Build and Push Image` stage if the target cluster uses a different architecture.

Optional ingress stages (`INSTALL_INGRESS_STACK`, `ENABLE_INGRESS_TLS`) deploy ingress-nginx + cert-manager and configure a Let's Encrypt TLS certificate; they are skipped by default.

Jenkins credentials required:
- `azure-sp` — service principal client ID (username) + secret (password)
- `azure-tenant-id` — Directory (tenant) ID
- `azure-subscription-id` — Subscription ID