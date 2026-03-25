# Users API Runbook

คู่มือนี้เป็น cheat sheet สำหรับงานที่ต้องใช้บ่อยกับโปรเจกต์นี้

## 1. ค่าหลักของระบบ

- Resource Group: `rg-users-api`
- ACR: `usersapiacr`
- AKS: `aks-users-api`
- Namespace: `users-api`
- Image repo: `users-api`
- Jenkins agent VM: `jenkins-linux-agent`

## 2. Deploy ใหม่ผ่าน Jenkins

ใน Jenkins job `users-api-pipeline` ใช้ค่าหลักนี้:

- `ACR_NAME = usersapiacr`
- `ACR_LOGIN_SERVER = usersapiacr.azurecr.io`
- `IMAGE_REPO = users-api`
- `AKS_RESOURCE_GROUP = rg-users-api`
- `AKS_CLUSTER_NAME = aks-users-api`
- `K8S_NAMESPACE = users-api`
- `ENABLE_INGRESS_TLS = false`
- `INSTALL_INGRESS_STACK = false`

## 3. คำสั่งเช็กสถานะบน AKS

รันบน Azure VM agent

### ดู deployment

```bash
kubectl -n users-api get deploy
kubectl -n users-api describe deployment users-api
```

### ดู pods

```bash
kubectl -n users-api get pods -o wide
kubectl -n users-api describe pod -l app=users-api
```

### ดู service

```bash
kubectl -n users-api get svc users-api -o wide
```

### ดู logs

```bash
kubectl -n users-api logs deployment/users-api --all-containers=true --tail=200
```

### ดู image ที่ deployment ใช้อยู่

```bash
kubectl -n users-api get deploy users-api -o jsonpath="{.spec.template.spec.containers[0].image}"
```

## 4. Public API

ถ้า service เป็น `LoadBalancer` ให้ดู public IP:

```bash
kubectl -n users-api get svc users-api -o wide
```

เรียก API:

```bash
curl http://<EXTERNAL-IP>/api/v1/users
curl http://<EXTERNAL-IP>/actuator/health/readiness
curl http://<EXTERNAL-IP>/actuator/health/liveness
```

## 5. Scale ลงเมื่อไม่ใช้

### ปิด app ชั่วคราว

```bash
kubectl -n users-api scale deploy users-api --replicas=0
kubectl -n users-api get pods
```

### เปิดกลับ

```bash
kubectl -n users-api scale deploy users-api --replicas=2
kubectl -n users-api rollout status deployment/users-api --timeout=180s
kubectl -n users-api get pods -o wide
```

## 6. Jenkins agent VM

### หยุด VM เพื่อลดค่าใช้จ่าย

ใน Azure Portal:

1. เข้า `jenkins-linux-agent`
2. หน้า `Overview`
3. กด `Stop`
4. รอให้เป็น `Stopped (deallocated)`

### กลับมาใช้งานใหม่

1. กด `Start`
2. SSH เข้า VM
3. รัน Jenkins agent command ใหม่

ตัวอย่าง:

```bash
cd /home/azureuser/jenkins-agent
java -jar agent.jar -url <JENKINS_URL> -secret <SECRET> -name "linux-docker-agent" -webSocket -workDir "/home/azureuser/jenkins-agent"
```

## 7. ACR

### ดู repositories

```bash
az acr repository list -n usersapiacr -o table
```

### ดู tags ของ image

```bash
az acr repository show-tags -n usersapiacr --repository users-api -o table
```

### ลบ image tag เก่า

```bash
az acr repository delete -n usersapiacr --image users-api:<TAG> --yes
```

ตัวอย่าง:

```bash
az acr repository delete -n usersapiacr --image users-api:11-57ee229 --yes
az acr repository delete -n usersapiacr --image users-api:12-c1c039e --yes
```

## 8. AKS กับ ACR

### เช็กว่า AKS ใช้ credential ได้

```bash
az aks get-credentials -g rg-users-api -n aks-users-api --overwrite-existing
```

### เช็กว่า AKS pull ACR ได้

```bash
az aks check-acr -g rg-users-api -n aks-users-api --acr usersapiacr.azurecr.io
```

## 9. ปัญหาที่เจอบ่อย

### `ImagePullBackOff`

เช็ก:

```bash
kubectl -n users-api describe pod -l app=users-api
```

สาเหตุที่เจอมาก:

- AKS pull image จาก ACR ไม่ได้
- tag ไม่ถูก
- image manifest ไม่ตรง architecture

### `CrashLoopBackOff`

เช็ก:

```bash
kubectl -n users-api logs deployment/users-api --all-containers=true --tail=200
```

สาเหตุที่เจอมาก:

- app start ไม่ขึ้น
- image architecture ไม่ตรง node
- readiness/liveness probe ไม่ผ่าน

### rollout timeout

เช็ก:

```bash
kubectl -n users-api rollout status deployment/users-api --timeout=180s
kubectl -n users-api get pods -o wide
kubectl -n users-api describe pod -l app=users-api
```

## 10. Local code workflow

### run tests

```bash
./mvnw -B clean test
```

### run app

```bash
./mvnw spring-boot:run
```

### build docker

```bash
docker build -t users-api:local .
docker run --rm -p 8080:8080 users-api:local
```

## 11. คำสั่ง cleanup ที่ใช้บ่อย

### ลบ pod เก่าทิ้ง

```bash
kubectl -n users-api delete pod -l app=users-api
```

### ดู replica set

```bash
kubectl -n users-api get rs
```

### ลบ namespace ทั้งก้อน

ใช้ด้วยความระวัง

```bash
kubectl delete namespace users-api
```

## 12. สรุปใช้งานเร็วที่สุด

### Deploy

1. push code
2. run Jenkins pipeline
3. เช็ก pods
4. เช็ก service public IP

### ปิดชั่วคราว

1. `kubectl -n users-api scale deploy users-api --replicas=0`
2. Stop `jenkins-linux-agent`

### เปิดกลับ

1. Start `jenkins-linux-agent`
2. run agent command
3. `kubectl -n users-api scale deploy users-api --replicas=2`
