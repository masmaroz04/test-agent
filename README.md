# Users API Deployment Guide

คู่มือนี้สรุป flow ทั้งหมดของโปรเจกต์นี้แบบ end-to-end ตั้งแต่โค้ด, Docker, Azure, AKS, Jenkins, ปัญหาที่เจอระหว่างทำจริง, วิธี deploy, วิธีเปิด public API, และวิธีลดค่าใช้จ่าย

## 1. โปรเจกต์นี้คืออะไร

โปรเจกต์นี้เป็น Spring Boot API สำหรับ `users` และ deploy ขึ้น Azure โดยใช้ Jenkins pipeline

flow หลักคือ:

1. Jenkins checkout source code
2. รัน test และ package ด้วย Maven
3. build Docker image
4. push image ไป Azure Container Registry (ACR)
5. deploy image ไป Azure Kubernetes Service (AKS)
6. เปิด API ผ่าน Kubernetes Service
7. ถ้าต้องการ สามารถต่อยอดไป Ingress + HTTPS ได้

## 2. โครงสร้างที่ใช้จริง

ตอนนี้ resource หลักที่ใช้คือ:

- Resource Group: `rg-users-api`
- ACR: `usersapiacr1`
- AKS: `aks-users-api`
- Kubernetes namespace: `users-api`
- Jenkins Linux agent VM: `jenkins-linux-agent`

### โครงสร้างการทำงาน

- source code อยู่ใน GitHub
- Jenkins controller รัน local
- Jenkins agent รันบน Azure VM Ubuntu
- agent ตัวนี้มี `Java 17`, `Docker`, `az`, `kubectl`
- Jenkins ใช้ agent นี้ build/push/deploy

## 3. ไฟล์สำคัญใน repo

- `Jenkinsfile`
  กำหนด pipeline ทั้งหมด
- `Dockerfile`
  ใช้ build image ของแอป
- `k8s/namespace.yaml`
  สร้าง namespace
- `k8s/deployment.yaml`
  สร้าง deployment ของแอป
- `k8s/service.yaml`
  เปิด service ของแอป
- `k8s/cluster-issuer.yaml`
  ใช้สำหรับ cert-manager / Let's Encrypt
- `k8s/ingress.yaml`
  ใช้สำหรับ domain + HTTPS

## 4. API ที่มี

- `GET /api/v1/users`
- `GET /api/v1/users/{id}`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

## 5. วิธีรันในเครื่อง

### Run tests

```bash
./mvnw -B clean test
```

### Run app locally

```bash
./mvnw spring-boot:run
curl http://localhost:8080/api/v1/users
```

### Verify Docker image locally

```bash
docker build -t users-api:local .
docker run --rm -p 8080:8080 users-api:local
curl http://localhost:8080/api/v1/users
```

## 6. สิ่งที่ Jenkins agent ต้องมี

บน Linux agent VM ต้องติดตั้ง:

- Java 17
- Docker
- Azure CLI (`az`)
- `kubectl`

## 7. Jenkins credentials ที่ต้องมี

ต้องสร้างใน Jenkins:

- `azure-sp`
  - type: `Username with password`
  - username = Azure Service Principal `appId`
  - password = Azure Service Principal `password`
- `azure-tenant-id`
  - type: `Secret text`
- `azure-subscription-id`
  - type: `Secret text`

## 8. Jenkins pipeline ทำอะไรบ้าง

`Jenkinsfile` ปัจจุบันทำงานตามนี้:

1. `Checkout`
2. `Test`
3. `Package`
4. `Azure Login`
5. `Build and Push Image`
6. `Deploy Workload to AKS`
7. ถ้าเปิด option ค่อย `Install Ingress Stack`
8. ถ้าเปิด option ค่อย `Deploy HTTPS Ingress`

### default parameters ปัจจุบัน

- `ACR_NAME = usersapiacr1`
- `ACR_LOGIN_SERVER = usersapiacr1.azurecr.io`
- `IMAGE_REPO = users-api`
- `AKS_RESOURCE_GROUP = rg-users-api`
- `AKS_CLUSTER_NAME = aks-users-api`
- `K8S_NAMESPACE = users-api`
- `ENABLE_INGRESS_TLS = false`
- `INSTALL_INGRESS_STACK = false`

## 9. สิ่งที่แก้ใน Jenkinsfile ระหว่างทำจริง

ระหว่าง setup จริง มีการแก้ `Jenkinsfile` เพื่อให้ pipeline ใช้งานกับ environment นี้ได้จริง

### 9.1 normalize ค่า ACR

รองรับกรณีกรอก `ACR_NAME` เป็น:

- `usersapiacr1`
- หรือ `usersapiacr1.azurecr.io`

pipeline จะ normalize ก่อน `az acr login`

### 9.2 validate parameter ก่อน deploy

ถ้า parameter สำคัญว่าง เช่น:

- `AKS_RESOURCE_GROUP`
- `AKS_CLUSTER_NAME`
- `K8S_NAMESPACE`

pipeline จะ fail ด้วยข้อความชัดเจน

### 9.3 inject parameter เข้า shell stage แบบตรง ๆ

แก้จากการพึ่ง shell env ที่บางครั้งไม่เห็นค่า parameter มาเป็นการใช้ `params.*` โดยตรงใน `sh """ ... """`

### 9.4 build image ให้ตรง architecture ของ AKS

AKS node ที่ใช้จริงเป็น ARM (`Standard_D2ps_v6`)  
ดังนั้น pipeline ต้อง build image เป็น:

```bash
docker buildx build --platform linux/arm64 --provenance=false -t "$FULL_IMAGE" --push .
```

ถ้า build เป็น `linux/amd64` จะเกิด:

```text
exec format error
```

## 10. ปัญหาที่เจอจริงและวิธีแก้

### 10.1 ACR login พังเพราะชื่อ registry ผิด format

อาการ:

```text
Registry names may contain only alpha numeric characters
```

สาเหตุ:
- ส่ง `ACR_NAME` เป็น full login server หรือค่าไม่ตรง format

วิธีแก้:
- normalize ชื่อ ACR ใน `Jenkinsfile`

### 10.2 `az aks get-credentials` ไม่เห็นค่า parameter

อาการ:

```text
az aks get-credentials --resource-group  --name
```

สาเหตุ:
- Jenkins parameter ไม่ถูก inject เข้า shell stage แบบที่คาด

วิธีแก้:
- ใช้ `params.AKS_RESOURCE_GROUP`, `params.AKS_CLUSTER_NAME`, `params.K8S_NAMESPACE` ตรง ๆ ใน `sh """ ... """`

### 10.3 Jenkins service principal ไม่มีสิทธิ์กับ AKS

อาการ:

```text
AuthorizationFailed ... listClusterUserCredential
```

วิธีแก้:
- assign role `Azure Kubernetes Service Cluster User Role`
ให้ service principal ของ Jenkins บน resource `aks-users-api`

### 10.4 AKS ดึง image จาก ACR ไม่ได้

อาการ:

```text
ImagePullBackOff
401 Unauthorized
```

วิธีแก้:
- attach ACR กับ AKS
- ตรวจสอบว่า AKS pull image จาก `usersapiacr1` ได้

### 10.5 image architecture ไม่ตรงกับ node

อาการ:

```text
exec /opt/java/openjdk/bin/java: exec format error
```

สาเหตุ:
- image เป็น `amd64`
- แต่ AKS node เป็น `arm64`

วิธีแก้:
- build image เป็น `linux/arm64`

## 11. สถานะปัจจุบันของ service

ตอนนี้ `k8s/service.yaml` เป็น:

```yaml
type: LoadBalancer
```

ดังนั้น pipeline จะได้ public IP จาก AKS service แล้วเรียก API ได้ตรง ๆ

### ตัวอย่าง

หลัง deploy สำเร็จ:

```bash
kubectl -n users-api get svc users-api -o wide
```

จะได้ประมาณ:

```text
EXTERNAL-IP   20.198.186.128
```

เรียก API:

```bash
curl http://20.198.186.128/api/v1/users
```

## 12. ถ้าอยากใช้ domain + HTTPS

ระบบรองรับแล้วผ่าน:

- `k8s/cluster-issuer.yaml`
- `k8s/ingress.yaml`
- parameters ใน `Jenkinsfile`

สิ่งที่ต้องมีเพิ่ม:

1. domain จริง
2. DNS `A record` ชี้มาที่ ingress public IP
3. เปิด Jenkins parameters:
   - `INSTALL_INGRESS_STACK = true`
   - `ENABLE_INGRESS_TLS = true`
   - `INGRESS_HOST = api.yourdomain.com`
   - `LETSENCRYPT_EMAIL = your@email.com`

### domain + HTTPS ดีกว่า public IP ยังไง

- URL จำง่ายกว่า
- เป็น HTTPS
- ดูเป็น production มากกว่า
- เปลี่ยน IP ภายหลังได้โดยแก้ DNS

## 13. วิธีดู image ใน Azure

ใน Azure Portal:

1. เข้า ACR `usersapiacr1`
2. ไปเมนู `Services` หรือ `Repositories`
3. เลือก repository `users-api`
4. ดู tags เช่น:
   - `11-57ee229`
   - `12-c1c039e`
   - `13-dfaebaa`

หรือใช้ command:

```bash
az acr repository list -n usersapiacr1 -o table
az acr repository show-tags -n usersapiacr1 --repository users-api -o table
```

## 14. วิธี scale deployment เป็น 0

### แนะนำที่สุด: ใช้ kubectl

รันบน Azure VM agent:

```bash
kubectl -n users-api scale deploy users-api --replicas=0
```

เช็ก:

```bash
kubectl -n users-api get deploy
kubectl -n users-api get pods
```

### เปิดกลับมาใหม่

```bash
kubectl -n users-api scale deploy users-api --replicas=2
kubectl -n users-api rollout status deployment/users-api --timeout=180s
```

### ทำใน Azure Portal ได้ไหม

ได้ แต่ UI เปลี่ยนบ่อย และไม่ตรงทุกหน้าของ AKS  
ถ้าจะทำผ่าน Portal ให้ดูในส่วน:

- `Kubernetes resources`
- `Workloads`
- `Deployments`
- เลือก `users-api`
- เปลี่ยน replica count

แต่สำหรับการฝึกและใช้งานจริง `kubectl scale` ตรงและชัดที่สุด

## 15. วิธีลดค่าใช้จ่าย

### 15.1 หยุด Jenkins VM

resource:

- `jenkins-linux-agent`

ใน Azure Portal:

1. เข้า VM
2. หน้า `Overview`
3. กด `Stop`
4. รอจน status เป็น:

```text
Stopped (deallocated)
```

ผล:
- หยุดค่า compute ของ VM
- Jenkins agent จะ offline

### 15.2 scale app ลงเมื่อไม่ใช้

```bash
kubectl -n users-api scale deploy users-api --replicas=0
```

ผล:
- pod ของแอปหยุด
- แต่ AKS cluster ยังมีค่าใช้จ่าย

### 15.3 ลบ image/tag เก่าที่ไม่ใช้

เช่น tag ที่ fail ไปก่อนหน้า:

- `11-57ee229`
- `12-c1c039e`

ลบด้วย:

```bash
az acr repository delete -n usersapiacr1 --image users-api:11-57ee229 --yes
az acr repository delete -n usersapiacr1 --image users-api:12-c1c039e --yes
```

### 15.4 สิ่งที่ stop ไม่ได้ง่าย ๆ

- AKS
- ACR

สองตัวนี้ไม่มีปุ่ม `Stop` แบบ VM  
ถ้าจะลดหนักจริง ๆ ต้อง scale down หรือ delete

## 16. วิธีกลับมาใช้งานใหม่

### ถ้า stop VM ไปแล้ว

1. กด `Start` ที่ `jenkins-linux-agent`
2. SSH เข้า VM
3. รัน Jenkins agent command ใหม่
4. เช็กว่า Jenkins node online

### ถ้า scale deployment เป็น 0 ไปแล้ว

```bash
kubectl -n users-api scale deploy users-api --replicas=2
kubectl -n users-api rollout status deployment/users-api --timeout=180s
kubectl -n users-api get svc users-api -o wide
```

## 17. คำสั่งที่ใช้บ่อย

### ดู pod

```bash
kubectl -n users-api get pods -o wide
```

### ดู deployment

```bash
kubectl -n users-api get deploy
kubectl -n users-api describe deployment users-api
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

## 18. สรุปสั้นที่สุด

ตอนนี้ระบบทำงานได้แล้วในรูปแบบนี้:

- Jenkins local
- Jenkins agent บน Azure VM
- build ARM image
- push ไป ACR
- deploy ไป AKS
- เปิด public API ผ่าน `LoadBalancer`

ถ้าจะฝึกต่อ แนะนำลำดับนี้:

1. ฝึก build/deploy ซ้ำให้คล่อง
2. ฝึก scale deployment เป็น `0` และเปิดกลับ
3. ฝึก cleanup image/tag เก่า
4. ค่อยต่อยอดไป domain + HTTPS
