# Users API — Zero to Production Guide

คู่มือนี้อธิบายทุกขั้นตอนตั้งแต่เริ่มต้นจนถึง production สำหรับโปรเจกต์ Spring Boot Users API ที่ deploy บน Azure Kubernetes Service ผ่าน Jenkins CI/CD

---

## สารบัญ

1. [โปรเจกต์นี้คืออะไร](#1-โปรเจกต์นี้คืออะไร)
2. [ภาพรวมสถาปัตยกรรม](#2-ภาพรวมสถาปัตยกรรม)
3. [ค่าหลักของระบบ](#3-ค่าหลักของระบบ)
4. [สิ่งที่ต้องเตรียมก่อน](#4-สิ่งที่ต้องเตรียมก่อน)
5. [ขั้นตอนที่ 1 — สร้าง Azure Resource Group](#5-ขั้นตอนที่-1--สร้าง-azure-resource-group)
6. [ขั้นตอนที่ 2 — สร้าง Azure Container Registry](#6-ขั้นตอนที่-2--สร้าง-azure-container-registry)
7. [ขั้นตอนที่ 3 — สร้าง Azure Kubernetes Service](#7-ขั้นตอนที่-3--สร้าง-azure-kubernetes-service)
8. [ขั้นตอนที่ 4 — สร้าง Jenkins Agent VM](#8-ขั้นตอนที่-4--สร้าง-jenkins-agent-vm)
9. [ขั้นตอนที่ 5 — สร้าง App Registration และ Service Principal](#9-ขั้นตอนที่-5--สร้าง-app-registration-และ-service-principal)
10. [ขั้นตอนที่ 6 — กำหนด Role ให้ Service Principal](#10-ขั้นตอนที่-6--กำหนด-role-ให้-service-principal)
11. [ขั้นตอนที่ 7 — ติดตั้ง Jenkins Controller](#11-ขั้นตอนที่-7--ติดตั้ง-jenkins-controller)
12. [ขั้นตอนที่ 8 — ติดตั้งเครื่องมือบน Jenkins Agent VM](#12-ขั้นตอนที่-8--ติดตั้งเครื่องมือบน-jenkins-agent-vm)
13. [ขั้นตอนที่ 9 — ผูก Agent VM เข้า Jenkins](#13-ขั้นตอนที่-9--ผูก-agent-vm-เข้า-jenkins)
14. [ขั้นตอนที่ 10 — สร้าง Jenkins Credentials](#14-ขั้นตอนที่-10--สร้าง-jenkins-credentials)
15. [ขั้นตอนที่ 11 — สร้าง Jenkins Pipeline Job](#15-ขั้นตอนที่-11--สร้าง-jenkins-pipeline-job)
16. [ขั้นตอนที่ 12 — Deploy ครั้งแรก](#16-ขั้นตอนที่-12--deploy-ครั้งแรก)
17. [เช็กหลัง Deploy](#17-เช็กหลัง-deploy)
18. [ทดสอบ API](#18-ทดสอบ-api)
19. [เปิด HTTPS (ตัวเลือกเสริม)](#19-เปิด-https-ตัวเลือกเสริม)
20. [ปัญหาที่เจอบ่อยและวิธีแก้](#20-ปัญหาที่เจอบ่อยและวิธีแก้)
21. [คำสั่งที่ใช้บ่อย](#21-คำสั่งที่ใช้บ่อย)
22. [Auth0 Authentication](#22-auth0-authentication)
23. [Monitoring — Prometheus + Grafana + Loki](#23-monitoring--prometheus--grafana--loki)
24. [Database — PostgreSQL บน AKS](#24-database--postgresql-บน-aks)

---

## 1. โปรเจกต์นี้คืออะไร

โปรเจกต์นี้เป็น **Spring Boot REST API** สำหรับ `users` พร้อม CI/CD pipeline ครบวงจร

**Stack หลัก:**

| ส่วน | เทคโนโลยี |
|---|---|
| Backend | Spring Boot 4, Java 17 |
| Frontend | Node.js + Express + express-openid-connect |
| Authentication | Auth0 (OAuth2 Authorization Code Flow + JWT) |
| Container | Docker (multi-stage build) |
| Registry | Azure Container Registry (ACR) |
| Orchestration | Azure Kubernetes Service (AKS) |
| CI/CD | Jenkins |
| Infrastructure | Azure |
| Monitoring | Prometheus + Grafana + Loki |
| Database | PostgreSQL 16 (pod ใน AKS) |

**API Endpoints (ต้อง Bearer token ทุก endpoint ยกเว้น health):**

```
GET  /api/v1/users        ดูรายชื่อ users ทั้งหมด  (ต้อง JWT + email ใน allowed-emails)
GET  /api/v1/users/{id}   ดู user ตาม ID           (ต้อง JWT + email ใน allowed-emails)
GET  /actuator/health/readiness
GET  /actuator/health/liveness
```

**Frontend:**

```
http://<FRONTEND_IP>/         หน้า home — login / logout / แสดง users
http://<FRONTEND_IP>/callback  Auth0 callback URL
```

**สิ่งที่ pipeline ทำทุกครั้งที่รัน:**

```
1.  checkout source code จาก Git
2.  รัน unit tests ด้วย Maven
3.  package เป็น JAR
4.  login Azure ด้วย Service Principal
5.  build Docker image สำหรับ Spring Boot API (linux/amd64) → push to ACR
6.  build Docker image สำหรับ Node.js frontend → push to ACR
7.  ดึง AKS credentials
8.  apply Kubernetes manifests (namespace, deployment, service)
9.  deploy node-frontend พร้อม Auth0 secrets จาก Kubernetes Secret
10. รอจน pods พร้อม (Spring Boot + Node.js)
```

---

## 2. ภาพรวมสถาปัตยกรรม

```
Developer
    |
    | git push
    v
Git Repository (GitHub)
    |
    | webhook / manual trigger
    v
Jenkins Controller  <-------- ngrok tunnel (ถ้า local) หรือ public IP
    |
    | dispatch job
    v
Jenkins Agent VM (Azure Ubuntu)
    |
    |-- ./mvnw test / package
    |-- az login (service principal)
    |-- az acr login
    |-- docker buildx build --platform linux/amd64 --push
    |-- az aks get-credentials
    |-- kubectl apply
    |
    +-------------------> Azure Container Registry
    |                      usersapiacr.azurecr.io/users-api:<BUILD_NUMBER>
    |
    +-------------------> Azure Kubernetes Service
                           aks-users-api
                               |
                           Namespace: users-api
                               |
                           Deployment: users-api (Spring Boot)
                           Deployment: node-frontend (Node.js)
                               |
                           Service: users-api (LoadBalancer) → Spring Boot API
                           Service: node-frontend (LoadBalancer) → Frontend UI
                               |
                           Public IP → อินเทอร์เน็ต

Browser → node-frontend (login Auth0) → ได้ Access Token
        → เรียก Spring Boot API พร้อม Bearer token
        → Spring Boot validate JWT กับ Auth0 JWKS endpoint
```

---

## 3. ค่าหลักของระบบ

ค่าเหล่านี้ใช้อ้างอิงตลอด guide นี้:

| ชื่อ | ค่า |
|---|---|
| Resource Group | `rg-users-api` |
| Region | `southeastasia` |
| ACR Name | `usersapiacr` |
| ACR Login Server | `usersapiacr.azurecr.io` |
| AKS Cluster | `aks-users-api` |
| K8s Namespace | `users-api` |
| Image Repo | `users-api` |
| Jenkins Agent VM | `jenkins-linux-agent` |
| Service Principal | `jenkins-users-api-sp` |
| Jenkins Agent Node | `linux-docker-agent` |
| Jenkins Agent Labels | `linux docker` |

---

## 4. สิ่งที่ต้องเตรียมก่อน

**บนเครื่องที่จะใช้งาน:**

- [Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli) ติดตั้งแล้ว
- Azure Account ที่มี Subscription และ permission สร้าง resource ได้
- Git
- Java 17+ (ถ้าต้องการรัน local)

**ตรวจสอบ Azure CLI:**

```bash
az --version
az login
az account show
```

**ถ้ามี Subscription หลายอัน เลือกอันที่ต้องการ:**

```bash
az account list -o table
az account set --subscription "<subscription-id>"
```

---

## 5. ขั้นตอนที่ 1 — สร้าง Azure Resource Group

Resource Group คือกล่องที่เก็บ resource ทั้งหมดของโปรเจกต์นี้ไว้ด้วยกัน

```bash
az group create \
  --name rg-users-api \
  --location southeastasia
```

ตรวจสอบ:

```bash
az group show --name rg-users-api -o table
```

---

## 6. ขั้นตอนที่ 2 — สร้าง Azure Container Registry

ACR ใช้เก็บ Docker image ที่ Jenkins build แล้ว push ขึ้นไป AKS จะ pull image จากที่นี่

```bash
az acr create \
  --resource-group rg-users-api \
  --name usersapiacr \
  --sku Standard \
  --admin-enabled false
```

ตรวจสอบ:

```bash
az acr show --name usersapiacr --query loginServer -o tsv
# ควรได้: usersapiacr.azurecr.io
```

**สิ่งที่ควรรู้:**

- ชื่อ ACR ต้องเป็น lowercase ตัวเลขและตัวอักษรเท่านั้น ห้ามมีขีด
- `--sku Standard` เพียงพอสำหรับโปรเจกต์นี้
- `--admin-enabled false` ดีกว่าเพราะเราใช้ Service Principal แทน

---

## 7. ขั้นตอนที่ 3 — สร้าง Azure Kubernetes Service

AKS คือ cluster ที่รัน application จริง

**สร้างผ่าน CLI:**

```bash
az aks create \
  --resource-group rg-users-api \
  --name aks-users-api \
  --node-count 1 \
  --node-vm-size Standard_D2s_v4 \
  --attach-acr usersapiacr \
  --generate-ssh-keys \
  --enable-managed-identity
```

ตรวจสอบ:

```bash
az aks show --resource-group rg-users-api --name aks-users-api --query provisioningState -o tsv
# ควรได้: Succeeded
```

**ค่าที่แนะนำถ้าสร้างผ่าน Azure Portal:**

| ฟิลด์ | ค่า |
|---|---|
| Cluster name | `aks-users-api` |
| Region | `Southeast Asia` |
| Architecture | `x64` |
| Node size | `Standard_D2s_v4` หรือ x64 ที่ quota ผ่าน |
| Node count | `1` |
| Autoscaling | ปิด |
| Authentication | `Local accounts with Kubernetes RBAC` |
| Container registry (Integrations tab) | เลือก `usersapiacr` |

**สิ่งสำคัญ:**

- ต้องใช้ node `x64` เพราะ pipeline build image เป็น `linux/amd64`
- ถ้า Azure ขึ้น quota error ให้ลองเปลี่ยน size เป็น `Standard_B2s` หรือ `Standard_D2as_v5`
- `--attach-acr` หรือการเลือก ACR ใน Integrations tab จะทำให้ AKS pull image จาก ACR ได้โดยอัตโนมัติ

---

## 8. ขั้นตอนที่ 4 — สร้าง Jenkins Agent VM

VM นี้จะทำหน้าที่เป็น Jenkins Agent ที่รัน pipeline จริง (build, test, push, deploy)

**สร้างผ่าน CLI:**

```bash
az vm create \
  --resource-group rg-users-api \
  --name jenkins-linux-agent \
  --image Ubuntu2404 \
  --size Standard_D2s_v4 \
  --admin-username azureuser \
  --generate-ssh-keys \
  --public-ip-sku Standard
```

**หรือสร้างผ่าน Azure Portal ด้วยค่าเหล่านี้:**

| ฟิลด์ | ค่า |
|---|---|
| VM name | `jenkins-linux-agent` |
| Image | `Ubuntu Server 24.04 LTS` |
| Architecture | `x64` |
| Size | `Standard_D2s_v4` หรือ x64 ที่ quota ผ่าน |
| Authentication | `SSH public key` |
| Public inbound ports | `SSH (22)` |

ดู Public IP ของ VM:

```bash
az vm show \
  --resource-group rg-users-api \
  --name jenkins-linux-agent \
  --show-details \
  --query publicIps -o tsv
```

**คำเตือน:**

- การเปิด port 22 จากทุก IP จะมี warning ใน Azure Portal
- หลังใช้งานเสร็จ ควรจำกัด NSG ให้รับแค่ IP ของผู้ดูแล

---

## 9. ขั้นตอนที่ 5 — สร้าง App Registration และ Service Principal

Service Principal คือ "bot account" ที่ Jenkins ใช้ login Azure แทน user จริง

### 9.1 สร้าง App Registration ผ่าน Azure Portal

1. ไปที่ [portal.azure.com](https://portal.azure.com)
2. ค้นหา **Microsoft Entra ID** ใน search bar ด้านบน → กด Enter
3. เมนูซ้าย → **App registrations** → กด **+ New registration**
4. กรอกข้อมูล:
   - Name: `jenkins-users-api-sp`
   - Supported account types: เลือก `Accounts in this organizational directory only`
   - Redirect URI: ปล่อยว่าง
5. กด **Register**

### 9.2 เก็บค่า Client ID และ Tenant ID

หลัง Register เสร็จจะเข้าสู่หน้า Overview ของ App registration

```
หน้า Overview จะเห็น:
┌──────────────────────────────────────────────────────────┐
│ Display name      : jenkins-users-api-sp                 │
│ Application (client) ID  : xxxxxxxx-xxxx-xxxx-xxxxxxxxx  │  ← คัดลอกไว้ (Client ID)
│ Directory (tenant) ID    : xxxxxxxx-xxxx-xxxx-xxxxxxxxx  │  ← คัดลอกไว้ (Tenant ID)
└──────────────────────────────────────────────────────────┘
```

คัดลอกทั้ง 2 ค่าไปเก็บไว้

### 9.3 สร้าง Client Secret

1. เมนูซ้าย → **Certificates & secrets**
2. Tab **Client secrets** → กด **+ New client secret**
3. กรอกข้อมูล:
   - Description: `jenkins-secret`
   - Expires: `24 months`
4. กด **Add**
5. จะเห็นตารางใหม่ขึ้นมา:

```
┌────────────────┬──────────────────────────────────────┬──────────────────┐
│ Description    │ Value                                │ Secret ID        │
├────────────────┼──────────────────────────────────────┼──────────────────┤
│ jenkins-secret │ xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx│ xxxxxxxx-...     │
└────────────────┴──────────────────────────────────────┴──────────────────┘
```

> **สำคัญมาก:** คัดลอก **Value** ทันที — หลังปิดหน้านี้จะเห็นแค่ `*****` และต้องสร้างใหม่

### 9.4 เก็บค่า Subscription ID

1. ค้นหา **Subscriptions** ใน search bar ด้านบน → กด Enter
2. คลิก Subscription ที่ต้องการใช้
3. หน้า Overview จะเห็น:

```
┌──────────────────────────────────────────────────┐
│ Subscription ID : xxxxxxxx-xxxx-xxxx-xxxxxxxxxxxx │  ← คัดลอกไว้
└──────────────────────────────────────────────────┘
```

### 9.5 สรุปค่าที่ต้องเก็บไว้ครบ 4 ค่า

```
Client ID        = xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx   (จาก App registration → Overview)
Client Secret    = xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx (จาก Certificates & secrets → Value)
Tenant ID        = xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx   (จาก App registration → Overview)
Subscription ID  = xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx   (จาก Subscriptions)
```

---

## 10. ขั้นตอนที่ 6 — กำหนด Role ให้ Service Principal

Jenkins ต้องมีสิทธิ์ push image ไป ACR และ deploy ไป AKS

### Role ที่ต้องให้

| Role | Scope | เหตุผล |
|---|---|---|
| `AcrPush` | ACR `usersapiacr` | push image ขึ้น ACR |
| `Azure Kubernetes Service Cluster User Role` | AKS `aks-users-api` | ดึง kubeconfig ของ AKS |

### วิธีที่ 1 — ผ่าน Azure Portal (ทำซ้ำ 2 รอบ)

**รอบที่ 1 — ให้สิทธิ์ AcrPush บน ACR:**

1. ไปที่ **Container registries** → `usersapiacr`
2. เลือก **Access control (IAM)** → **Add role assignment**
3. Role: `AcrPush`
4. Members: เลือก `jenkins-users-api-sp`
5. กด **Review + assign**

**รอบที่ 2 — ให้สิทธิ์ AKS บน AKS:**

1. ไปที่ **Kubernetes services** → `aks-users-api`
2. เลือก **Access control (IAM)** → **Add role assignment**
3. Role: `Azure Kubernetes Service Cluster User Role`
4. Members: เลือก `jenkins-users-api-sp`
5. กด **Review + assign**

### วิธีที่ 2 — ผ่าน Azure CLI

```bash
# กำหนดตัวแปร
SP_APP_ID="<client-id>"
ACR_ID=$(az acr show -g rg-users-api -n usersapiacr --query id -o tsv)
AKS_ID=$(az aks show -g rg-users-api -n aks-users-api --query id -o tsv)

# ให้สิทธิ์ push image ไป ACR
az role assignment create \
  --assignee "$SP_APP_ID" \
  --role AcrPush \
  --scope "$ACR_ID"

# ให้สิทธิ์เข้า AKS
az role assignment create \
  --assignee "$SP_APP_ID" \
  --role "Azure Kubernetes Service Cluster User Role" \
  --scope "$AKS_ID"
```

ตรวจสอบ:

```bash
az role assignment list --assignee "$SP_APP_ID" -o table
```

---

## 11. ขั้นตอนที่ 7 — ติดตั้ง Jenkins Controller

Jenkins Controller คือ server หลักที่จัดการ pipeline และ UI มีหลายวิธีในการติดตั้ง

### วิธีที่ 1 — รันผ่าน Docker (แนะนำสำหรับ local / dev)

```bash
docker run -d \
  --name jenkins \
  -p 8082:8080 \
  -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  jenkins/jenkins:lts
```

ดู initial admin password:

```bash
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

เปิด browser ไปที่ `http://localhost:8082` แล้วทำตามขั้นตอน setup

### วิธีที่ 2 — ติดตั้งบน Ubuntu VM

```bash
# ติดตั้ง Java ก่อน
sudo apt-get update
sudo apt-get install -y openjdk-17-jre-headless

# เพิ่ม Jenkins repo
sudo wget -O /usr/share/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key

echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] \
  https://pkg.jenkins.io/debian-stable binary/" | \
  sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null

sudo apt-get update
sudo apt-get install -y jenkins

sudo systemctl start jenkins
sudo systemctl enable jenkins

# ดู initial password
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
```

### Jenkins อยู่บน local แต่ Agent อยู่บน Azure VM

ถ้า Jenkins Controller อยู่บนเครื่อง local ที่ไม่มี public IP ต้องใช้ **ngrok** เพื่อ expose ออกอินเทอร์เน็ต

**ติดตั้ง ngrok:**

```bash
# Windows — ดาวน์โหลดจาก https://ngrok.com/download
# หรือ
choco install ngrok
```

**ตั้งค่า authtoken (ทำครั้งเดียว):**

```bash
ngrok config add-authtoken <your-authtoken>
```

**เปิด tunnel:**

```bash
ngrok http 8082
# จะได้ URL เช่น: https://xxxx.ngrok-free.app
```

**อัปเดต Jenkins URL:**

ไปที่ **Manage Jenkins** → **System** → **Jenkins URL**

เปลี่ยนเป็น ngrok URL เช่น `https://xxxx.ngrok-free.app` แล้ว Save

> **หมายเหตุ:** Free plan ของ ngrok URL จะเปลี่ยนทุกครั้งที่ restart ต้องอัปเดต Jenkins URL ใหม่ทุกครั้ง

---

## 12. ขั้นตอนที่ 8 — ติดตั้งเครื่องมือบน Jenkins Agent VM

SSH เข้า VM ก่อน — ดู Public IP จาก Azure Portal → **Virtual machines** → `jenkins-linux-agent` → **Overview** → **Public IP address**

```bash
ssh -i <path-to-private-key> azureuser@<PUBLIC_IP_OF_VM>
```

> **Windows — ข้อควรระวัง:**
> - ถ้าใช้ **PowerShell**: `ssh -i "$env:USERPROFILE\Downloads\jenkins-linux-agent_key.pem" azureuser@<IP>`
> - ถ้าใช้ **Command Prompt (cmd)**: `ssh -i "C:\Users\<username>\Downloads\jenkins-linux-agent_key.pem" azureuser@<IP>`
> - `$env:USERPROFILE` ใช้ได้เฉพาะ PowerShell เท่านั้น ใน cmd ต้องใส่ path เต็มๆ

### 12.1 อัปเดต package และติดตั้งพื้นฐาน + Java

```bash
sudo apt-get update && sudo apt-get upgrade -y
sudo apt-get install -y \
  ca-certificates curl gnupg lsb-release git \
  unzip apt-transport-https software-properties-common \
  openjdk-17-jre-headless

# ตรวจสอบ
java -version
```

### 12.2 ติดตั้ง Docker Engine + Buildx Plugin

```bash
# เพิ่ม Docker GPG key
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# เพิ่ม Docker repo
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# ติดตั้ง Docker
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin

# เพิ่ม user เข้ากลุ่ม docker (ไม่ต้อง sudo ตอนรัน docker)
sudo usermod -aG docker azureuser
```

**ออกจาก SSH แล้วเข้าใหม่ 1 รอบ** เพื่อให้ group มีผล:

```bash
exit
ssh -i <path-to-private-key> azureuser@<PUBLIC_IP_OF_VM>
```

ตรวจสอบ:

```bash
docker version
docker buildx version
docker run --rm hello-world
```

### 12.3 ติดตั้ง Azure CLI

```bash
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# ตรวจสอบ
az version
```

### 12.4 ติดตั้ง kubectl

```bash
# เพิ่ม Kubernetes GPG key
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.34/deb/Release.key | \
  sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
sudo chmod a+r /etc/apt/keyrings/kubernetes-apt-keyring.gpg

# เพิ่ม Kubernetes repo
echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] \
  https://pkgs.k8s.io/core:/stable:/v1.34/deb/ /' | \
  sudo tee /etc/apt/sources.list.d/kubernetes.list

# ติดตั้ง
sudo apt-get update
sudo apt-get install -y kubectl

# ตรวจสอบ
kubectl version --client
```

### 12.5 สรุปตรวจสอบทุก tool

```bash
java -version
docker version
docker buildx version
az version
kubectl version --client
```

ทุกอันต้องตอบกลับเวอร์ชันได้ ไม่มี error

---

## 13. ขั้นตอนที่ 9 — ผูก Agent VM เข้า Jenkins

### 13.1 สร้าง Node ใน Jenkins

1. ไปที่ **Manage Jenkins** → **Nodes** → **New Node**
2. Node name: `linux-docker-agent`
3. เลือก `Permanent Agent`
4. กด **Create**

**ตั้งค่า Node:**

| ฟิลด์ | ค่า |
|---|---|
| Number of executors | `1` |
| Remote root directory | `/home/azureuser/jenkins-agent` |
| Labels | `linux docker` |
| Usage | `Use this node as much as possible` |
| Launch method | `Launch agent by connecting it to the controller` |

กด **Save**

### 13.2 Copy คำสั่ง Connect จาก Jenkins

เปิด Node ที่เพิ่งสร้าง จะเห็นคำสั่งแบบนี้:

```bash
curl -sO http://<JENKINS_URL>/jnlpJars/agent.jar
java -jar agent.jar \
  -url http://<JENKINS_URL>/ \
  -secret <SECRET_FROM_JENKINS> \
  -name "linux-docker-agent" \
  -webSocket \
  -workDir "/home/azureuser/jenkins-agent"
```

### 13.3 รันคำสั่งบน Agent VM

SSH เข้า VM แล้วรัน:

```bash
mkdir -p /home/azureuser/jenkins-agent
cd /home/azureuser/jenkins-agent

# ใช้ URL จริงจาก Jenkins (ngrok URL หรือ public IP)
curl -sO https://<JENKINS_URL>/jnlpJars/agent.jar

nohup java -jar agent.jar \
  -url https://<JENKINS_URL>/ \
  -secret <SECRET_FROM_JENKINS> \
  -name "linux-docker-agent" \
  -webSocket \
  -workDir "/home/azureuser/jenkins-agent" > agent.log 2>&1 &
```

เมื่อ connect สำเร็จ Jenkins จะแสดง **Agent is connected.**

**ทำไมต้องใช้ `nohup ... &`:**

| วิธีรัน | พฤติกรรม |
|---|---|
| `java -jar agent.jar ...` (ไม่มี nohup) | ถ้าปิด terminal หรือ SSH หลุด → process ตายทันที agent ออฟไลน์ |
| `nohup java -jar agent.jar ... &` | process รันต่อแม้ปิด terminal — `nohup` กัน SIGHUP, `&` รัน background |

**ดู log ของ agent:**

```bash
tail -f ~/jenkins-agent/agent.log
```

**เช็ก process ว่ารันอยู่:**

```bash
ps aux | grep agent.jar
```

### 13.4 ทำให้ Agent รันตลอดเวลา (systemd service)

สร้าง service file:

```bash
sudo nano /etc/systemd/system/jenkins-agent.service
```

ใส่ content:

```ini
[Unit]
Description=Jenkins Agent
After=network.target

[Service]
User=azureuser
WorkingDirectory=/home/azureuser/jenkins-agent
ExecStart=/usr/bin/java -jar /home/azureuser/jenkins-agent/agent.jar \
  -url https://<JENKINS_URL>/ \
  -secret <SECRET_FROM_JENKINS> \
  -name "linux-docker-agent" \
  -webSocket \
  -workDir /home/azureuser/jenkins-agent
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

เปิดใช้งาน:

```bash
sudo systemctl daemon-reload
sudo systemctl enable jenkins-agent
sudo systemctl start jenkins-agent

# เช็กสถานะ
sudo systemctl status jenkins-agent
```

---

## 14. ขั้นตอนที่ 10 — สร้าง Jenkins Credentials

ค่าทั้งหมดในขั้นตอนนี้ได้มาจาก **ขั้นตอนที่ 5** (App Registration) ที่ทำไปแล้ว

### เปิดหน้า Credentials ใน Jenkins

1. Jenkins หน้าแรก → **Manage Jenkins**
2. เลือก **Credentials**
3. คลิก **(global)** ใต้ Stores scoped to Jenkins
4. เมนูซ้าย → **Add Credentials**

ต้องสร้างครบ 3 อัน ทำซ้ำ 3 รอบ:

---

### 14.1 `azure-sp` — เก็บ Client ID และ Client Secret

**ค่าเหล่านี้มาจากไหน:**

```
Microsoft Entra ID → App registrations → jenkins-users-api-sp

Username = Application (client) ID   (หน้า Overview)
Password = Client secret Value        (หน้า Certificates & secrets → Value)
```

**ใส่ใน Jenkins:**

| ฟิลด์ | ค่า |
|---|---|
| Kind | `Username with password` |
| Scope | `Global` |
| Username | วางค่า `Application (client) ID` |
| Password | วางค่า `Client secret Value` |
| ID | `azure-sp` |
| Description | `Azure Service Principal` |

กด **Create**

---

### 14.2 `azure-tenant-id` — เก็บ Tenant ID

**ค่านี้มาจากไหน:**

```
Microsoft Entra ID → App registrations → jenkins-users-api-sp

Secret = Directory (tenant) ID   (หน้า Overview)
```

**ใส่ใน Jenkins:**

| ฟิลด์ | ค่า |
|---|---|
| Kind | `Secret text` |
| Scope | `Global` |
| Secret | วางค่า `Directory (tenant) ID` |
| ID | `azure-tenant-id` |
| Description | `Azure Tenant ID` |

กด **Create**

---

### 14.3 `azure-subscription-id` — เก็บ Subscription ID

**ค่านี้มาจากไหน:**

```
Subscriptions → เลือก Subscription ที่ใช้

Secret = Subscription ID   (หน้า Overview)
```

**ใส่ใน Jenkins:**

| ฟิลด์ | ค่า |
|---|---|
| Kind | `Secret text` |
| Scope | `Global` |
| Secret | วางค่า `Subscription ID` |
| ID | `azure-subscription-id` |
| Description | `Azure Subscription ID` |

กด **Create**

---

---

### 14.4 `auth0-client-secret` — เก็บ Auth0 Client Secret

**ค่านี้มาจากไหน:**

```
Auth0 Dashboard → Applications → My App → Settings

Secret = Client Secret   (กด icon ตาเพื่อ reveal)
```

**ใส่ใน Jenkins:**

| ฟิลด์ | ค่า |
|---|---|
| Kind | `Secret text` |
| Scope | `Global` |
| Secret | วางค่า Client Secret จาก Auth0 |
| ID | `auth0-client-secret` |
| Description | `Auth0 My App Client Secret` |

กด **Create**

---

### ตรวจสอบว่าครบ

กลับไปหน้า **Credentials** → **(global)** ควรเห็นครบ 4 รายการ:

```
azure-sp                  Username with password
azure-tenant-id           Secret text
azure-subscription-id     Secret text
auth0-client-secret       Secret text
```

---

## 15. ขั้นตอนที่ 11 — สร้าง Jenkins Pipeline Job

### 15.1 สร้าง Pipeline Job

1. Jenkins หน้าแรก → **New Item**
2. ชื่อ: `users-api-pipeline`
3. เลือก `Pipeline`
4. กด **OK**

### 15.2 ตั้งค่า Pipeline

ใน Configuration:

**General:**
- เปิด `This project is parameterized` — ไม่ต้องทำเพราะ Jenkinsfile จัดการให้แล้ว

**Pipeline:**
- Definition: `Pipeline script from SCM`
- SCM: `Git`
- Repository URL: URL ของ Git repo นี้
- Branch: `*/main`
- Script Path: `Jenkinsfile`

กด **Save**

---

## 16. ขั้นตอนที่ 12 — Deploy ครั้งแรก

### 16.1 ตรวจสอบ Checklist ก่อน Build

- [ ] Jenkins Agent Node แสดงสถานะ **online** (สีเขียว)
- [ ] Credentials ครบ 4 อัน (`azure-sp`, `azure-tenant-id`, `azure-subscription-id`, `auth0-client-secret`)
- [ ] Service Principal มี role ครบ (`AcrPush` + `Azure Kubernetes Service Cluster User Role`)
- [ ] AKS cluster สถานะ `Succeeded`
- [ ] ACR สร้างเสร็จแล้ว
- [ ] Auth0 My App มี Callback URL และ Logout URL ตรงกับ Frontend IP

### 16.2 กด Build

1. เปิด Job `users-api-pipeline`
2. กด **Build with Parameters**
3. ตรวจสอบค่า default และกรอก Auth0 parameters:

```
ACR_NAME                = usersapiacr
ACR_LOGIN_SERVER        = usersapiacr.azurecr.io
IMAGE_REPO              = users-api
AKS_RESOURCE_GROUP      = rg-users-api
AKS_CLUSTER_NAME        = aks-users-api
K8S_NAMESPACE           = users-api
ENABLE_INGRESS_TLS      = false
INSTALL_INGRESS_STACK   = false

# Auth0 / Frontend (ต้องกรอกเอง)
FRONTEND_IMAGE_REPO     = node-frontend
FRONTEND_BASE_URL       = http://<FRONTEND_EXTERNAL_IP>  ← ดูจาก deploy รอบแรก
AUTH0_ISSUER_BASE_URL   = https://dev-s4q7d35k5uyh6r3n.eu.auth0.com
AUTH0_AUDIENCE          = https://users-api
AUTH0_CLIENT_ID         = VJVLUuEmBQVE7AJmwwm2HkbJkEH0vBmC
```

> **Deploy ครั้งแรก** ปล่อย `FRONTEND_BASE_URL` ว่างไว้ก่อน → หลัง deploy ดู External IP จาก `kubectl -n users-api get svc node-frontend` → run pipeline รอบที่ 2 ใส่ IP จริง + อัปเดต Auth0 Callback URL

4. กด **Build**

### 16.3 Pipeline Stages ที่จะเห็น

```
✅ Checkout              — clone source code
✅ Test                  — ./mvnw -B clean test
✅ Package               — ./mvnw -B package -DskipTests
✅ Azure Login           — az login --service-principal
✅ Build and Push Image  — build Spring Boot image + Node.js frontend image → push to ACR
✅ Deploy Workload       — kubectl apply: namespace, deployment, service, node-frontend-secret
                           rollout status ทั้ง Spring Boot และ Node.js
⬜ Install Ingress Stack — (ถ้า INSTALL_INGRESS_STACK=true)
⬜ Deploy HTTPS Ingress  — (ถ้า ENABLE_INGRESS_TLS=true)
```

ถ้าทุก stage เป็นสีเขียว แสดงว่า deploy สำเร็จ

---

## 17. เช็กหลัง Deploy

รันคำสั่งเหล่านี้บน Jenkins Agent VM หรือเครื่องที่มี kubectl ที่ผูกกับ AKS แล้ว:

```bash
# ดึง kubeconfig ก่อน (ถ้ายังไม่ได้ดึง)
az aks get-credentials \
  --resource-group rg-users-api \
  --name aks-users-api \
  --overwrite-existing

# เช็ก deployment
kubectl -n users-api get deploy

# เช็ก pods
kubectl -n users-api get pods -o wide

# เช็ก service และ Public IP
kubectl -n users-api get svc users-api -o wide

# เช็ก image ที่ใช้อยู่
kubectl -n users-api get deploy users-api \
  -o jsonpath="{.spec.template.spec.containers[0].image}"

# รอ pods พร้อม
kubectl -n users-api rollout status deployment/users-api --timeout=180s
```

**ผลลัพธ์ที่ควรเห็น:**

```
NAME        READY   UP-TO-DATE   AVAILABLE
users-api   2/2     2            2

NAME             EXTERNAL-IP      PORT(S)        AGE
users-api        20.x.x.x         80:xxxxx/TCP   2m
```

เมื่อ `EXTERNAL-IP` ปรากฏ แปลว่า app พร้อมใช้งานแล้ว

---

## 18. ทดสอบ API

ดู Public IP:

```bash
kubectl -n users-api get svc users-api -o jsonpath="{.status.loadBalancer.ingress[0].ip}"
```

เรียก API:

```bash
# ดู users ทั้งหมด
curl http://<EXTERNAL-IP>/api/v1/users

# ดู user ตาม ID
curl http://<EXTERNAL-IP>/api/v1/users/1

# Health check
curl http://<EXTERNAL-IP>/actuator/health/readiness
curl http://<EXTERNAL-IP>/actuator/health/liveness
```

**ผลลัพธ์ที่ควรได้:**

```json
[
  {"id": 1, "name": "Alice"},
  {"id": 2, "name": "Bob"}
]
```

```json
{"status": "UP"}
```

---

## 19. เปิด HTTPS (ตัวเลือกเสริม)

pipeline รองรับ HTTPS ผ่าน ingress-nginx + cert-manager อยู่แล้ว ต้องมีเพิ่ม:

- Domain name จริงที่ DNS ชี้มาที่ Public IP ของ LoadBalancer
- Email สำหรับ Let's Encrypt

**Build with Parameters แล้วตั้งค่าเพิ่ม:**

```
INSTALL_INGRESS_STACK = true      (ครั้งแรกเท่านั้น)
ENABLE_INGRESS_TLS    = true
INGRESS_HOST          = api.yourdomain.com
LETSENCRYPT_EMAIL     = your@email.com
TLS_SECRET_NAME       = users-api-tls
CLUSTER_ISSUER_NAME   = letsencrypt-prod
```

หลัง deploy:

```bash
# เช็ก ingress
kubectl -n users-api get ingress -o wide

# เช็ก certificate (รอสักครู่)
kubectl -n users-api get certificate

# เช็ก Public IP ของ ingress controller
kubectl -n ingress-nginx get svc ingress-nginx-controller -o wide
```

---

## 20. ปัญหาที่เจอบ่อยและวิธีแก้

### 20.1 `ImagePullBackOff`

```bash
kubectl -n users-api describe pod -l app=users-api
# ดู Events ด้านล่าง
```

| สาเหตุ | วิธีแก้ |
|---|---|
| AKS ไม่มีสิทธิ์ pull จาก ACR | ตรวจสอบว่าผูก ACR กับ AKS แล้ว (`az aks check-acr`) |
| image tag ไม่มีใน ACR | ดู tags ด้วย `az acr repository show-tags -n usersapiacr --repository users-api` |
| ชื่อ image ผิด | เช็ก `FULL_IMAGE` ใน Jenkins build log |

```bash
# เช็กว่า AKS pull ACR ได้
az aks check-acr \
  --resource-group rg-users-api \
  --name aks-users-api \
  --acr usersapiacr.azurecr.io
```

### 20.2 `CrashLoopBackOff`

```bash
kubectl -n users-api logs deployment/users-api --all-containers=true --tail=200
```

| สาเหตุ | วิธีแก้ |
|---|---|
| App start ไม่ขึ้น | ดู logs หา exception |
| Image architecture ไม่ตรง node | ตรวจสอบว่า pipeline build `linux/amd64` และ AKS node เป็น x64 |
| Health probe ไม่ผ่าน | เช็ก `/actuator/health/readiness` ตอบกลับได้ไหม |

### 20.3 `rollout status` timeout

```bash
kubectl -n users-api get pods -o wide
kubectl -n users-api describe pod -l app=users-api
kubectl -n users-api get events --sort-by='.lastTimestamp'
```

### 20.4 `az aks get-credentials` ไม่ผ่าน

```bash
# ทดสอบ login ด้วย service principal
az login \
  --service-principal \
  --username "<client-id>" \
  --password "<client-secret>" \
  --tenant "<tenant-id>"

az account set --subscription "<subscription-id>"

az aks get-credentials \
  --resource-group rg-users-api \
  --name aks-users-api \
  --overwrite-existing
```

| สาเหตุ | วิธีแก้ |
|---|---|
| ไม่มี role `Azure Kubernetes Service Cluster User Role` | เพิ่ม role ตาม section 10 |
| Subscription ID ผิด | เช็ก `azure-subscription-id` credential |
| ชื่อ resource group หรือ cluster ผิด | เช็ก parameter ใน Jenkins |

### 20.5 `az acr login` ไม่ผ่าน หรือ push ไม่ได้

```bash
# ทดสอบ
az acr login --name usersapiacr
```

| สาเหตุ | วิธีแก้ |
|---|---|
| ไม่มี role `AcrPush` | เพิ่ม role ตาม section 10 |
| ชื่อ ACR ผิด | ต้องเป็น lowercase ไม่มีขีด |
| Admin disabled และไม่มี SP | ใช้ service principal แทน |

### 20.6 Jenkins Agent ไม่ online

```bash
# เช็กสถานะ service บน VM
sudo systemctl status jenkins-agent

# ดู log
sudo journalctl -u jenkins-agent -n 50
```

| สาเหตุ | วิธีแก้ |
|---|---|
| Jenkins URL ผิด | อัปเดต URL ใน systemd service ให้ตรงกับ ngrok URL ปัจจุบัน |
| Secret ผิด | copy secret ใหม่จากหน้า Node ใน Jenkins |
| Port ถูก block | เช็ก firewall / NSG ของ VM |

### 20.7 `permission denied while trying to connect to the Docker daemon`

Error นี้เกิดเมื่อ user `azureuser` ยังไม่ได้อยู่ใน group `docker`

```bash
# เช็ก docker group ของ user
groups azureuser
```

**ถ้าไม่มี `docker` ใน output:**

```bash
# เพิ่ม user เข้า group docker
sudo usermod -aG docker azureuser
```

จากนั้น **ต้อง restart agent** เพื่อให้ group ใหม่มีผล (แค่ logout/login ไม่พอถ้า agent รันอยู่แล้ว):

```bash
# หา PID ของ agent
ps aux | grep agent.jar

# kill process เก่า
kill <PID>

# รัน agent ใหม่
cd ~/jenkins-agent
nohup java -jar agent.jar \
  -url https://<JENKINS_URL>/ \
  -secret <SECRET_FROM_JENKINS> \
  -name "linux-docker-agent" \
  -webSocket \
  -workDir "/home/azureuser/jenkins-agent" > agent.log 2>&1 &
```

รอ agent reconnect แล้ว Build ใหม่ใน Jenkins

---

## 21. คำสั่งที่ใช้บ่อย

### ACR

```bash
# ดู repositories ทั้งหมด
az acr repository list -n usersapiacr -o table

# ดู tags ของ image
az acr repository show-tags -n usersapiacr --repository users-api -o table

# ลบ image tag เก่า
az acr repository delete -n usersapiacr --image users-api:<TAG> --yes

# เช็กว่า AKS pull ACR ได้
az aks check-acr -g rg-users-api -n aks-users-api --acr usersapiacr.azurecr.io
```

### Kubernetes

```bash
# เช็กทุกอย่างใน namespace
kubectl -n users-api get all

# ดู pods พร้อม IP
kubectl -n users-api get pods -o wide

# ดู log แบบ follow
kubectl -n users-api logs deployment/users-api -f

# ดู events ล่าสุด
kubectl -n users-api get events --sort-by='.lastTimestamp'

# restart pods (rolling restart)
kubectl -n users-api rollout restart deployment/users-api

# scale ลงชั่วคราว (ประหยัดค่าใช้จ่าย)
kubectl -n users-api scale deploy users-api --replicas=0

# scale กลับ
kubectl -n users-api scale deploy users-api --replicas=2

# ดู image ที่ใช้อยู่
kubectl -n users-api get deploy users-api \
  -o jsonpath="{.spec.template.spec.containers[0].image}"
```

### Jenkins Agent VM

```bash
# start/stop VM เพื่อประหยัดค่าใช้จ่าย
az vm start  -g rg-users-api -n jenkins-linux-agent
az vm stop   -g rg-users-api -n jenkins-linux-agent --no-wait
az vm deallocate -g rg-users-api -n jenkins-linux-agent --no-wait

# เช็ก public IP หลัง start
az vm show -g rg-users-api -n jenkins-linux-agent \
  --show-details --query publicIps -o tsv
```

### Local Development

```bash
# รัน tests
./mvnw -B clean test

# รัน app local
./mvnw spring-boot:run
# เรียกที่ http://localhost:8080/api/v1/users

# build Docker image local
docker build -t users-api:local .
docker run --rm -p 8080:8080 users-api:local
```

---

## สรุปทั้งหมดใน 1 หน้า

**Azure resources ที่ต้องสร้าง (5 อย่าง):**

```
1. Resource Group    rg-users-api
2. ACR               usersapiacr
3. AKS               aks-users-api
4. Ubuntu VM         jenkins-linux-agent   (Jenkins Agent)
5. App Registration  jenkins-users-api-sp  (Service Principal)
```

**Roles ที่ต้องให้ Service Principal (2 อย่าง):**

```
1. AcrPush                                    → scope: ACR usersapiacr
2. Azure Kubernetes Service Cluster User Role → scope: AKS aks-users-api
```

**Jenkins Setup (3 อย่าง):**

```
1. Credentials ครบ 4 อัน (azure-sp, azure-tenant-id, azure-subscription-id, auth0-client-secret)
2. Node linux-docker-agent ที่มี labels: linux docker
3. Pipeline job ที่ใช้ Jenkinsfile จาก repo นี้
```

**Flow ประโยคเดียว:**

> Jenkins login Azure ด้วย Service Principal → build Docker image → push ไป ACR → ดึง AKS kubeconfig → apply Kubernetes manifests → app รันบน AKS พร้อม Public IP

---

## 22. Auth0 Authentication

ระบบนี้มี Auth0 JWT authentication ครบวงจร ประกอบด้วย 2 ส่วน:

| ส่วน | เทคโนโลยี | หน้าที่ |
|---|---|---|
| Spring Boot API | `spring-boot-starter-oauth2-resource-server` | Validate JWT Bearer token |
| Node.js frontend | Express + `express-openid-connect` | Login/logout ด้วย Auth0 แล้วเรียก API |

### ภาพรวม Flow

```
Browser → Node.js frontend (login ด้วย Auth0) → ได้ Access Token
         → เรียก Spring Boot API พร้อม Bearer token
         → Spring Boot validate token กับ Auth0 JWKS
         → ถ้าผ่าน → return users data
```

### Auth0 Objects ที่ต้องมี

ใน Auth0 Dashboard มี 2 object ที่ใช้:

**1. My App (Regular Web Application)**
- ใน `Applications → Applications → My App → Settings`
- ให้ค่า `Client ID` และ `Client Secret`
- ใช้โดย: Node.js frontend (`express-openid-connect`)
- ต้องตั้งค่า Callback URL และ Logout URL (ดูหัวข้อถัดไป)

**2. Users API (Custom API)**
- ใน `Applications → APIs → Users API`
- Identifier: `https://users-api` → ใช้เป็น `audience`
- ใช้โดย: Spring Boot resource server ตรวจว่า token ออกมาสำหรับ API นี้

### ตั้งค่า My App — Callback และ Logout URLs

ไปที่ `My App → Settings` เลื่อนลงหา **Application URIs**:

```
Allowed Callback URLs:
http://<FRONTEND_EXTERNAL_IP>/callback

Allowed Logout URLs:
http://<FRONTEND_EXTERNAL_IP>
```

> แทน `<FRONTEND_EXTERNAL_IP>` ด้วย External IP ของ `node-frontend` service หลัง deploy
> ถ้า deploy ในเครื่องก่อน ใส่ `http://localhost:3000/callback` และ `http://localhost:3000`

### Jenkins Credentials ที่ต้องเพิ่ม

เพิ่ม credential ใหม่ 1 อัน (นอกเหนือจาก azure-sp, azure-tenant-id, azure-subscription-id):

| Credential ID | Kind | ค่า | หาได้จาก |
|---|---|---|---|
| `auth0-client-secret` | Secret text | Client Secret ของ My App | Auth0 → Applications → My App → Settings → **Client Secret** (กด icon ตาเพื่อ reveal) |

### Jenkins Parameters ที่ต้องกรอกเพิ่ม

เมื่อ run pipeline ให้กรอก parameters เพิ่มเติม:

| Parameter | ค่า | หมายเหตุ |
|---|---|---|
| `FRONTEND_IMAGE_REPO` | `node-frontend` | ชื่อ repo ใน ACR |
| `FRONTEND_BASE_URL` | `http://<FRONTEND_EXTERNAL_IP>` | ดูจาก `kubectl get svc node-frontend` หลัง deploy ครั้งแรก |
| `AUTH0_ISSUER_BASE_URL` | `https://dev-s4q7d35k5uyh6r3n.eu.auth0.com` | Auth0 tenant domain |
| `AUTH0_AUDIENCE` | `https://users-api` | Users API Identifier |
| `AUTH0_CLIENT_ID` | `VJVLUuEmBQVE7AJmwwm2HkbJkEH0vBmC` | My App Client ID |

> `AUTH0_CLIENT_SECRET` ดึงจาก Jenkins credential `auth0-client-secret` โดยอัตโนมัติ ไม่ต้องกรอกใน parameter

### Deploy ครั้งแรก (2 รอบ)

เนื่องจาก `FRONTEND_BASE_URL` ต้องการ External IP ที่ได้จากการ deploy ก่อน:

**รอบที่ 1** — Deploy ด้วย `FRONTEND_BASE_URL` ว่างไว้ก่อน
```bash
# หลัง deploy ดู External IP ของ node-frontend
kubectl -n users-api get svc node-frontend
```

**รอบที่ 2** — ใส่ IP ที่ได้ใน `FRONTEND_BASE_URL` แล้ว run pipeline อีกครั้ง
พร้อมอัปเดต Auth0 Callback URL ด้วย

### ทดสอบ locally

```bash
cd node-frontend
npm install

# สร้าง .env
cat > .env << 'EOF'
SESSION_SECRET=any-random-string-32-chars-min
BASE_URL=http://localhost:3000
AUTH0_CLIENT_ID=VJVLUuEmBQVE7AJmwwm2HkbJkEH0vBmC
AUTH0_CLIENT_SECRET=<ดูจาก My App → Settings → Client Secret>
AUTH0_ISSUER_BASE_URL=https://dev-s4q7d35k5uyh6r3n.eu.auth0.com
AUTH0_AUDIENCE=https://users-api
USERS_API_URL=http://localhost:8080
PORT=3000
EOF

npm start
# เปิด http://localhost:3000
```

> ต้องเพิ่ม `http://localhost:3000/callback` ใน Auth0 My App Allowed Callback URLs ก่อน

### Authorization — Email Whitelist ด้วย @PreAuthorize

Spring Boot ใช้ `@PreAuthorize` + SpEL ตรวจ email ของ caller ก่อนให้เข้า endpoint:

```java
// UserController.java
@PreAuthorize("@auth0Properties.getAllowedEmails().get(0) == authentication.principal.claims['email']")
public List<User> getUsers() { ... }
```

- `@auth0Properties` — Spring Bean ชื่อ `auth0Properties` (class `Auth0Properties`)
- `getAllowedEmails()` — return `List<String>` จาก `application.yaml`
- `authentication.principal.claims['email']` — claim จาก JWT ที่ Auth0 inject ผ่าน Post Login Action
- ถ้า email ไม่ตรง → Spring던ว่า `AccessDeniedException` → `ApiExceptionHandler` จับ → return 403

**เพิ่ม/แก้ email ที่อนุญาต:**

```yaml
# src/main/resources/application.yaml
auth0:
  audience: https://users-api
  allowed-emails:
    - your@email.com
    - another@email.com
```

### Auth0 Post Login Action (inject claims เข้า Access Token)

ต้องตั้งค่า Action ใน Auth0 เพื่อให้ `name` และ `email` ปรากฏใน JWT:

1. Auth0 Dashboard → **Actions** → **Flows** → **Login**
2. กด **+** → **Build Custom** → ชื่อ `Inject User Claims`
3. ใส่ code:

```js
exports.onExecutePostLogin = async (event, api) => {
  api.accessToken.setCustomClaim('name',  event.user.name);
  api.accessToken.setCustomClaim('email', event.user.email);
};
```

4. **Deploy** → ลาก Action เข้า Login flow → **Apply**

### Logging

ทุก request ที่ผ่านการ authenticate จะถูก log โดย `JwtLoggingFilter`:

```
[JWT] Request authenticated — method=GET uri=/api/v1/users sub=auth0|xxx name=John email=john@example.com scope=openid profile email
```

ถ้า access denied จะ log ที่ `ApiExceptionHandler`:

```
[AUTH] Access denied — email=unauthorized@example.com
```

### ไฟล์ที่เกี่ยวข้อง

```
src/main/java/.../security/SecurityConfig.java    ← JWT decoder + audience validator
src/main/java/.../security/Auth0Properties.java   ← bind allowed-emails จาก YAML
src/main/java/.../security/JwtLoggingFilter.java  ← log ทุก authenticated request
src/main/java/.../user/UserController.java        ← @PreAuthorize email check
src/main/java/.../user/ApiExceptionHandler.java   ← handle 403 + log email
src/main/resources/application.yaml              ← Auth0 issuer URI + audience + allowed-emails
node-frontend/server.js                          ← Express + Auth0 login + เรียก API
node-frontend/Dockerfile                         ← Container image
k8s/node-frontend-deployment.yaml               ← AKS deployment
k8s/node-frontend-service.yaml                  ← LoadBalancer service
k8s/node-frontend-secret.yaml                   ← Kubernetes Secret สำหรับ Auth0 credentials
```

---

## 23. Monitoring — Prometheus + Grafana + Loki

### ภาพรวม

```
[users-api /actuator/prometheus] ─┐
[node-frontend /metrics]          ├──► Prometheus (metrics) ──► Grafana Dashboard
[K8s cluster metrics]            ─┘

[users-api stdout logs]    ─┐
[node-frontend stdout logs] ├──► Promtail ──► Loki (logs) ──► Grafana Explore
[K8s system logs]          ─┘
```

### Stack

| Component | หน้าที่ |
|---|---|
| **Prometheus** | scrape และเก็บ metrics ตัวเลข |
| **Grafana** | Dashboard UI แสดง metrics และ logs |
| **Loki** | เก็บ logs จากทุก pod |
| **Promtail** | ดึง logs จากทุก pod ส่งให้ Loki |
| **Alertmanager** | ส่ง alert เมื่อ metric ผิดปกติ |

### สิ่งที่เพิ่มใน Project

**pom.xml** — เพิ่ม Micrometer Prometheus dependency:
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**application.yaml** — เปิด `/actuator/prometheus` endpoint:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

**k8s/deployment.yaml และ k8s/node-frontend-deployment.yaml** — เพิ่ม Prometheus scrape annotations:
```yaml
annotations:
  prometheus.io/scrape: "true"
  prometheus.io/path: "/actuator/prometheus"   # หรือ /metrics สำหรับ Node.js
  prometheus.io/port: "8080"                   # หรือ 3000 สำหรับ Node.js
```

**node-frontend/server.js** — เพิ่ม `/metrics` endpoint ด้วย `prom-client`

### ติดตั้ง Monitoring Stack บน AKS

SSH เข้า Jenkins Agent VM แล้วรัน:

```bash
# 1. ติดตั้ง Helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# 2. เพิ่ม Helm repos
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update

# 3. ติดตั้ง Prometheus + Grafana + Alertmanager
helm install monitoring prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --create-namespace \
  --set prometheus.prometheusSpec.podMonitorSelectorNilUsesHelmValues=false \
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false

# 4. ติดตั้ง Loki
helm install loki grafana/loki \
  --namespace monitoring \
  --set loki.auth_enabled=false \
  --set loki.commonConfig.replication_factor=1 \
  --set loki.storage.type=filesystem \
  --set singleBinary.replicas=1 \
  --set deploymentMode=SingleBinary \
  --set backend.replicas=0 \
  --set read.replicas=0 \
  --set write.replicas=0 \
  --set loki.useTestSchema=true

# 5. ติดตั้ง Promtail (ดึง log จากทุก pod)
helm install promtail grafana/promtail \
  --namespace monitoring \
  --set config.lokiAddress=http://loki-gateway.monitoring.svc.cluster.local/loki/api/v1/push

# 6. เช็ค pods
kubectl get pods -n monitoring
```

### เข้า Grafana

```bash
# Port-forward (รันค้างไว้)
kubectl port-forward -n monitoring svc/monitoring-grafana 3001:80 &
```

เปิด browser: `http://localhost:3001`
- Username: `admin`
- Password: `prom-operator`

### ตั้งค่า Loki Datasource

1. **Connections → Data sources → Add data source → Loki**
2. URL: `http://loki-gateway.monitoring.svc.cluster.local`
3. กด **Save & Test**

### Import Dashboard สำเร็จรูป

**Dashboards → New → Import** แล้วใส่ ID:

| Dashboard ID | แสดงอะไร |
|---|---|
| **315** | Kubernetes cluster overview |
| **4701** | JVM (Spring Boot) — heap, GC, threads |
| **11159** | Node.js — memory, event loop, requests |

### ดู Logs ใน Grafana

1. **Explore** → เลือก datasource **Loki**
2. ใส่ query:

```
{namespace="users-api"}
```

จะเห็น log จากทั้ง `users-api` และ `node-frontend` แบบ real-time

---

## 24. Database — PostgreSQL บน AKS

### ภาพรวม

```
[users-api pod] ──connect──► [postgres pod]
  Java / Spring Data JPA        PostgreSQL 16
  deployment.yaml               postgres-deployment.yaml
```

PostgreSQL รันเป็น pod ใน AKS cluster เดียวกัน ไม่ต้องสร้าง Azure resource เพิ่ม

### ไฟล์ที่เกี่ยวข้อง

| ไฟล์ | หน้าที่ |
|---|---|
| `k8s/postgres-secret.yaml` | เก็บ DB name, username, password |
| `k8s/postgres-deployment.yaml` | deploy postgres pod (image: postgres:16-alpine จาก Docker Hub) |
| `k8s/postgres-service.yaml` | ClusterIP service ให้ users-api connect ได้ |
| `src/main/resources/data.sql` | seed ข้อมูล users เริ่มต้น |

### K8s Manifests

**postgres-secret.yaml** — เก็บ credentials:
```yaml
stringData:
  POSTGRES_DB: usersdb
  POSTGRES_USER: usersapp
  POSTGRES_PASSWORD: __POSTGRES_PASSWORD__   ← Jenkins inject ตอน deploy
```

**postgres-service.yaml** — internal DNS:
```
postgres.users-api.svc.cluster.local:5432
# users-api ใช้แค่ "postgres" เป็น DB_HOST ก็พอ
```

### Environment Variables ใน users-api

```yaml
- name: DB_HOST
  value: postgres                # ชื่อ K8s service
- name: DB_NAME
  valueFrom:
    secretKeyRef:
      name: postgres-secret
      key: POSTGRES_DB
- name: DB_USER
  valueFrom:
    secretKeyRef:
      name: postgres-secret
      key: POSTGRES_USER
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: postgres-secret
      key: POSTGRES_PASSWORD
```

### Jenkins Parameter

ตอน **Build with Parameters** จะมีช่อง:
```
POSTGRES_PASSWORD = usersapp   (default)
```

### เชื่อม DBeaver จาก Windows

Deploy เสร็จแล้วทำตามขั้นตอน:

**1. port-forward บน VM:**
```bash
kubectl port-forward -n users-api svc/postgres 5433:5432
```

**2. SSH tunnel จาก Windows (terminal ใหม่):**
```bash
ssh -i "C:\Users\patip\Downloads\jenkins-linux-agent_key.pem" -L 5433:localhost:5433 azureuser@20.212.32.185
```

**3. DBeaver connection:**
```
Host:     localhost
Port:     5433
Database: usersdb
User:     usersapp
Password: usersapp
```

### Flyway Database Migrations

ใช้ Flyway แทน `data.sql` เพื่อ track schema version และ seed data

**ไฟล์ migration อยู่ที่:**
```
src/main/resources/db/migration/
  V1__create_users_table.sql   ← สร้าง table
  V2__seed_users.sql           ← seed ข้อมูลเริ่มต้น
```

**การทำงาน:**
```
Deploy ทุกครั้ง → Flyway เช็ค flyway_schema_history ใน DB ก่อน
  → version ที่รันแล้ว → ข้าม
  → version ใหม่ที่ยังไม่รัน → รัน แล้วบันทึกใน history
```

**ตัวอย่าง flyway_schema_history (สร้างอัตโนมัติตอน deploy ครั้งแรก):**
```
version | description          | success
--------|----------------------|--------
1       | create users table   | true
2       | seed users           | true
```

**เพิ่ม column ในอนาคต:**
```sql
-- สร้างไฟล์ใหม่ V3__add_phone_column.sql
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
```
Flyway จะรัน V3 แค่ครั้งเดียว V1, V2 ไม่โดนรันซ้ำ

**กฎสำคัญ:** ห้ามแก้ไขไฟล์ V1, V2 ที่รันไปแล้ว — ถ้าอยากแก้ไขให้สร้าง version ใหม่เสมอ
