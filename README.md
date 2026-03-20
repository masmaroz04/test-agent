









# Users API Production Workflow (Jenkins + Azure + AKS + HTTPS)

This repository is ready for end-to-end deployment:

1. Build and test Spring Boot API
2. Build and push Docker image to Azure Container Registry (ACR)
3. Deploy to Azure Kubernetes Service (AKS)
4. Expose public HTTPS endpoint via Ingress + cert-manager (Let's Encrypt)

## API Endpoints

- `GET /api/v1/users`
- `GET /api/v1/users/{id}`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

## Main Files

- `Jenkinsfile`
- `Dockerfile`
- `k8s/namespace.yaml`
- `k8s/deployment.yaml`
- `k8s/service.yaml`
- `k8s/cluster-issuer.yaml`
- `k8s/ingress.yaml`

## Prerequisites

Jenkins agent needs:

- Docker
- Azure CLI (`az`)
- `kubectl`
- Java 17

Jenkins credentials:

- `azure-sp` (Username with password)
  - username = Service Principal `appId`
  - password = Service Principal `password`
- `azure-tenant-id` (Secret text)
- `azure-subscription-id` (Secret text)

## Local Verification

Run tests:

```bash
./mvnw -B clean test
```

Run app locally:

```bash
./mvnw spring-boot:run
curl http://localhost:8080/api/v1/users
```

## Docker Verification

```bash
docker build -t users-api:local .
docker run --rm -p 8080:8080 users-api:local
curl http://localhost:8080/api/v1/users
```

## Azure Setup (First Time)

Example with Azure CLI:

```bash
az group create -n rg-users-api -l southeastasia

az acr create \
  -g rg-users-api \
  -n <YOUR_ACR_NAME> \
  --sku Basic

az aks create \
  -g rg-users-api \
  -n aks-users-api \
  --node-count 2 \
  --generate-ssh-keys \
  --attach-acr <YOUR_ACR_NAME>
```

Create Service Principal for Jenkins:

```bash
az ad sp create-for-rbac \
  --name jenkins-users-api-sp \
  --role Contributor \
  --scopes /subscriptions/<SUBSCRIPTION_ID>/resourceGroups/rg-users-api
```

## Jenkins Pipeline Parameters

Required base parameters:

- `ACR_NAME`
- `ACR_LOGIN_SERVER`
- `IMAGE_REPO` (default: `users-api`)
- `AKS_RESOURCE_GROUP`
- `AKS_CLUSTER_NAME`
- `K8S_NAMESPACE` (default: `users-api`)

HTTPS/Ingress parameters:

- `ENABLE_INGRESS_TLS` = `true`
- `INSTALL_INGRESS_STACK` = `true` for first run
- `INGRESS_HOST` = your public DNS host, for example `api.yourdomain.com`
- `INGRESS_CLASS` = `nginx`
- `TLS_SECRET_NAME` = `users-api-tls`
- `CLUSTER_ISSUER_NAME` = `letsencrypt-prod`
- `LETSENCRYPT_EMAIL` = your email
- `ACME_SERVER` = `https://acme-v02.api.letsencrypt.org/directory`

## DNS Mapping for Public Internet Access

After first deploy, check ingress controller public IP:

```bash
kubectl -n ingress-nginx get svc ingress-nginx-controller
```

Create DNS `A` record:

- Host: `api` (or full host used in `INGRESS_HOST`)
- Value: ingress controller public IP

Wait for DNS propagation, then cert-manager can complete HTTP-01 challenge.

## Post-Deploy Checks

Check workload:

```bash
kubectl -n users-api get deploy,pod,svc,ingress
kubectl -n users-api get certificate
kubectl -n users-api describe certificate users-api-tls
```

Test from internet:

```bash
curl https://<INGRESS_HOST>/api/v1/users
curl https://<INGRESS_HOST>/actuator/health/readiness
```

If certificate is still pending, check challenge/order resources:

```bash
kubectl -n users-api get challenge,order
kubectl -n users-api describe challenge
```
