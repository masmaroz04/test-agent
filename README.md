# Users API Azure Setup Guide

คู่มือนี้อธิบายการเตรียม Azure, Jenkins, Jenkins agent VM, สิทธิ์ที่ต้องใช้, และวิธี deploy โปรเจกต์นี้ตั้งแต่ต้นจนจบ โดยอ้างอิงค่าจริงใน repo ปัจจุบัน

## 1. โปรเจกต์นี้คืออะไร

โปรเจกต์นี้เป็น Spring Boot REST API สำหรับ `users`

สิ่งที่ pipeline ทำคือ:

1. Jenkins checkout source
2. รัน test และ package
3. สร้าง Docker image
4. push image ไป Azure Container Registry
5. deploy image ไป Azure Kubernetes Service
6. expose app ผ่าน Kubernetes Service

ค่าหลักของระบบนี้คือ:

- Resource Group: `rg-users-api`
- ACR: `usersapiacr`
- ACR Login Server: `usersapiacr.azurecr.io`
- AKS: `aks-users-api`
- Namespace: `users-api`
- Image Repo: `users-api`
- Jenkins agent VM: `jenkins-linux-agent`

หมายเหตุสำคัญ:

- pipeline ปัจจุบัน build image เป็น `linux/amd64`
- เพราะฉะนั้น AKS node และ Jenkins agent VM ควรใช้สถาปัตยกรรม `x64`

## 2. ภาพรวมสถาปัตยกรรม

```text
Developer
   |
   v
Git Repo
   |
   v
Jenkins Controller
   |
   v
Jenkins Agent VM on Azure
   |-- mvn test/package
   |-- docker buildx build
   |-- az login
   |-- kubectl apply
   |
   +------------------> Azure Container Registry (usersapiacr)
   |
   +------------------> Azure Kubernetes Service (aks-users-api)
                                |
                                v
                           Namespace users-api
                                |
                                v
                           Deployment users-api
                                |
                                v
                                Pods
                                |
                                v
                           Service users-api
                                |
                                v
                             Public IP
```

## 3. สิ่งที่ต้องมีใน Azure

สำหรับ flow นี้ ต้องมี resource อย่างน้อย:

1. `Resource Group`
2. `Azure Container Registry`
3. `Azure Kubernetes Service`
4. `Ubuntu VM` สำหรับเป็น Jenkins agent
5. `App Registration / Service Principal`

ถ้าจะเปิด HTTPS ผ่าน Ingress ภายหลัง ค่อยเพิ่ม:

- ingress-nginx
- cert-manager
- DNS record

## 4. ลำดับการสร้างที่แนะนำ

ให้สร้างตามลำดับนี้:

1. สร้าง Resource Group
2. สร้าง ACR
3. สร้าง AKS และผูกกับ ACR
4. สร้าง Jenkins agent VM
5. สร้าง App Registration + Client Secret
6. Add role ให้ service principal
7. ติดตั้ง Java, Docker, Azure CLI, kubectl บน Jenkins agent VM
8. ผูก VM เข้า Jenkins เป็น agent
9. ใส่ Jenkins credentials
10. รัน pipeline deploy

## 5. สร้าง Resource Group

ตัวอย่าง:

```bash
az group create \
  --name rg-users-api \
  --location southeastasia
```

## 6. สร้าง Azure Container Registry

ใช้ชื่อ:

- Registry name: `usersapiacr`
- Login server: `usersapiacr.azurecr.io`

ตัวอย่าง:

```bash
az acr create \
  --resource-group rg-users-api \
  --name usersapiacr \
  --sku Standard \
  --admin-enabled false
```

สิ่งที่ควรรู้:

- ACR ใช้เก็บ image ที่ Jenkins push ขึ้นไป
- AKS จะ pull image จากที่นี่
- pipeline ใช้ค่า default นี้ใน [Jenkinsfile](/D:/workspaceboss/test_agent/test_agent/Jenkinsfile#L10)

## 7. สร้าง Azure Kubernetes Service

ใช้ค่าแนะนำ:

- Cluster name: `aks-users-api`
- Resource Group: `rg-users-api`
- Region: `Southeast Asia`
- Architecture: `x64`
- Node size: รุ่น x86 ที่ quota ผ่าน เช่น `Standard_D2s_v4`
- Node count: `1`
- Autoscaling: ปิดไว้ก่อน
- Authentication: `Local accounts with Kubernetes RBAC`
- Container registry: เลือก `usersapiacr`

ตัวอย่างแนวคิด:

```bash
az aks create \
  --resource-group rg-users-api \
  --name aks-users-api \
  --node-count 1 \
  --node-vm-size Standard_D2s_v4 \
  --attach-acr usersapiacr \
  --generate-ssh-keys
```

สิ่งที่ควรรู้:

- ถ้า Azure ขึ้น quota error ให้เปลี่ยน VM size ไปตระกูลที่ subscription ใช้ได้
- เพราะ pipeline build `amd64` แล้ว ไม่จำเป็นต้องบังคับใช้ ARM node
- ตอนสร้าง AKS ถ้าเลือก ACR ไว้ในหน้า Integrations จะช่วยให้ AKS pull image จาก ACR ได้ง่ายขึ้น

## 8. สร้าง Jenkins Agent VM

แนวทางที่ใช้ในโปรเจกต์นี้คือ:

- Jenkins controller รันอยู่ที่อื่น
- Azure VM ตัวนี้รันเป็น Jenkins agent

ค่าแนะนำ:

- VM name: `jenkins-linux-agent`
- Image: `Ubuntu Server 24.04 LTS`
- Architecture: `x64`
- Size: `Standard_D2s_v3`, `Standard_D2s_v4` หรือรุ่นใกล้เคียงที่ quota ผ่าน
- Authentication: `SSH public key`
- Public inbound ports: `SSH (22)` เพื่อให้เข้าไปติดตั้งได้

คำเตือน:

- ถ้าเปิด `SSH (22)` จาก internet ทุก IP จะมี warning ใน Portal
- ใช้งานเริ่มต้นได้ แต่หลังจากสร้างเสร็จควรจำกัด source IP ใน NSG ให้เป็น IP ของผู้ดูแล

## 9. สร้าง App Registration สำหรับ Jenkins

ไปที่:

`Microsoft Entra ID -> App registrations -> New registration`

ใช้ชื่อแนะนำ:

- `jenkins-users-api-sp`

จากนั้น:

1. เปิด App registration ที่สร้าง
2. จดค่า `Application (client) ID`
3. จดค่า `Directory (tenant) ID`
4. ไปที่ `Certificates & secrets`
5. สร้าง `Client secret`
6. จดค่า `Client secret value`
7. ไปที่หน้า Subscription แล้วจด `Subscription ID`

ค่าที่ต้องได้สุดท้ายมี 4 ค่า:

- Client ID
- Client Secret
- Tenant ID
- Subscription ID

## 10. ต้อง add role อะไรบ้าง

ตัวนี้เป็นจุดสำคัญที่สุดหลังสร้าง Azure resource แล้ว

service principal ของ Jenkins ต้องมีสิทธิ์อย่างน้อย:

1. `AcrPush`
2. `Azure Kubernetes Service Cluster User Role`

### 10.1 `AcrPush`

scope:

- ให้ที่ ACR `usersapiacr`

เหตุผล:

- ใช้สำหรับ `az acr login`
- ใช้สำหรับ push image เข้า ACR

### 10.2 `Azure Kubernetes Service Cluster User Role`

scope:

- ให้ที่ AKS `aks-users-api`

เหตุผล:

- ใช้สำหรับ `az aks get-credentials`
- ทำให้ Jenkins ดึง kubeconfig ของ AKS มาใช้ได้

### 10.3 วิธี add role ผ่าน Azure Portal

ทำซ้ำ 2 รอบตาม role ที่ต้องให้:

1. เปิด resource ที่ต้องการ เช่น ACR หรือ AKS
2. ไปที่ `Access control (IAM)`
3. กด `Add role assignment`
4. เลือก role
5. เลือก assignee เป็น service principal `jenkins-users-api-sp`
6. กด save

### 10.4 ตัวอย่าง add role ผ่าน Azure CLI

ให้แทนค่าตัวแปรก่อน:

```bash
SUBSCRIPTION_ID="<subscription-id>"
SP_APP_ID="<client-id>"
ACR_ID="$(az acr show -g rg-users-api -n usersapiacr --query id -o tsv)"
AKS_ID="$(az aks show -g rg-users-api -n aks-users-api --query id -o tsv)"
```

จากนั้นรัน:

```bash
az role assignment create \
  --assignee "$SP_APP_ID" \
  --role AcrPush \
  --scope "$ACR_ID"

az role assignment create \
  --assignee "$SP_APP_ID" \
  --role "Azure Kubernetes Service Cluster User Role" \
  --scope "$AKS_ID"
```

### 10.5 ถ้า Jenkins deploy แล้วยังโดน Forbidden

ส่วนใหญ่จะเป็น 2 กลุ่ม:

1. ยัง add Azure role ไม่ครบ
2. ดึง kubeconfig ได้ แต่สิทธิ์ใน Kubernetes ยังไม่พอ

แนวทางแก้ที่พบบ่อย:

- เช็กก่อนว่า `az aks get-credentials` ผ่านไหม
- ถ้า `kubectl apply` ติด `Forbidden` ให้พิจารณาใช้ admin credential ชั่วคราว หรือผูก Kubernetes RBAC เพิ่ม

สำหรับ repo นี้ Jenkinsfile ปัจจุบันใช้:

```bash
az aks get-credentials --resource-group ... --name ...
```

ไม่ได้ใช้ `--admin`

## 11. ติดตั้งเครื่องมือบน Jenkins agent VM

SSH เข้า VM ก่อน:

```bash
ssh -i <path-to-private-key> azureuser@<PUBLIC_IP>
```

ลำดับที่แนะนำ:

1. ลง package พื้นฐานและ Java
2. ลง Docker และ buildx
3. logout แล้ว SSH เข้าใหม่ 1 รอบ
4. เช็กว่า `docker` ใช้ได้โดยไม่ต้องมี `sudo`
5. ลง Azure CLI
6. ลง kubectl

### 11.1 update package และติดตั้งพื้นฐาน

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg lsb-release git unzip apt-transport-https software-properties-common openjdk-17-jre-headless
java -version
```

### 11.2 ติดตั้ง Docker Engine + Buildx

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo \"$VERSION_CODENAME\") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin
sudo usermod -aG docker azureuser
```

ออกจาก SSH แล้วเข้าใหม่ 1 รอบ เพื่อให้ group `docker` มีผล

จากนั้นเช็ก:

```bash
docker version
docker buildx version
```

### 11.3 ติดตั้ง Azure CLI

```bash
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash
az version
```

### 11.4 ติดตั้ง kubectl

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.34/deb/Release.key | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
sudo chmod a+r /etc/apt/keyrings/kubernetes-apt-keyring.gpg

echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.34/deb/ /' | \
  sudo tee /etc/apt/sources.list.d/kubernetes.list

sudo apt-get update
sudo apt-get install -y kubectl
kubectl version --client
```

### 11.5 เช็ก Java

```bash
java -version
```

## 12. ผูก VM เข้า Jenkins เป็น agent

บน Jenkins controller:

1. ไปที่ `Manage Jenkins`
2. ไปที่ `Nodes`
3. สร้าง node ใหม่ เช่น `linux-docker-agent`
4. ใส่ labels ให้ตรงกับ pipeline เช่น `linux docker`
5. เลือก launch method เป็น `Launch agent by connecting it to the controller`
6. copy command ที่ Jenkins สร้างให้

ตัวอย่าง command:

```bash
mkdir -p /home/azureuser/jenkins-agent
cd /home/azureuser/jenkins-agent
curl -O https://<JENKINS_URL>/jnlpJars/agent.jar
java -jar agent.jar \
  -url <JENKINS_URL> \
  -secret <SECRET> \
  -name "linux-docker-agent" \
  -webSocket \
  -workDir "/home/azureuser/jenkins-agent"
```

ถ้าต้องการให้รันถาวร แนะนำทำเป็น systemd service

ตัวอย่าง:

```ini
[Unit]
Description=Jenkins Agent
After=network.target

[Service]
User=azureuser
WorkingDirectory=/home/azureuser/jenkins-agent
ExecStart=/usr/bin/java -jar /home/azureuser/jenkins-agent/agent.jar -url <JENKINS_URL> -secret <SECRET> -name "linux-docker-agent" -webSocket -workDir "/home/azureuser/jenkins-agent"
Restart=always

[Install]
WantedBy=multi-user.target
```

## 13. สร้าง Jenkins Credentials

บน Jenkins สร้าง credentials ตามนี้:

### 13.1 `azure-sp`

ชนิด:

- `Username with password`

mapping:

- Username = `Application (client) ID`
- Password = `Client secret value`

### 13.2 `azure-tenant-id`

ชนิด:

- `Secret text`

ค่า:

- `Directory (tenant) ID`

### 13.3 `azure-subscription-id`

ชนิด:

- `Secret text`

ค่า:

- `Subscription ID`

## 14. ค่าที่ Jenkins pipeline ใช้

ค่า default ปัจจุบันใน [Jenkinsfile](/D:/workspaceboss/test_agent/test_agent/Jenkinsfile#L9):

- `ACR_NAME = usersapiacr`
- `ACR_LOGIN_SERVER = usersapiacr.azurecr.io`
- `IMAGE_REPO = users-api`
- `AKS_RESOURCE_GROUP = rg-users-api`
- `AKS_CLUSTER_NAME = aks-users-api`
- `K8S_NAMESPACE = users-api`
- `ENABLE_INGRESS_TLS = false`
- `INSTALL_INGRESS_STACK = false`

จุดสำคัญ:

- pipeline ใช้ `linux && docker` เป็น agent label
- node ใน Jenkins จึงควรมี labels อย่างน้อย `linux docker`

## 15. Deploy ครั้งแรก

เมื่อทุกอย่างพร้อมแล้ว ให้รัน Jenkins pipeline

ลำดับที่ pipeline ทำ:

1. `./mvnw -B clean test`
2. `./mvnw -B package -DskipTests`
3. `az login --service-principal`
4. `az account set --subscription`
5. `az acr login`
6. `docker buildx build --platform linux/amd64 --push`
7. `az aks get-credentials`
8. `kubectl apply` namespace, deployment, service
9. `kubectl rollout status`

## 16. เช็กหลัง deploy

รันบน Jenkins agent VM หรือเครื่องที่มีสิทธิ์เข้า AKS:

```bash
az aks get-credentials -g rg-users-api -n aks-users-api --overwrite-existing
kubectl -n users-api get deploy
kubectl -n users-api get pods -o wide
kubectl -n users-api get svc users-api -o wide
kubectl -n users-api rollout status deployment/users-api --timeout=180s
```

เช็ก image ที่ deployment ใช้อยู่:

```bash
kubectl -n users-api get deploy users-api -o jsonpath="{.spec.template.spec.containers[0].image}"
```

## 17. ทดสอบ API

ถ้า service เป็น `LoadBalancer`:

```bash
kubectl -n users-api get svc users-api -o wide
```

จากนั้นเรียก:

```bash
curl http://<EXTERNAL-IP>/api/v1/users
curl http://<EXTERNAL-IP>/actuator/health/readiness
curl http://<EXTERNAL-IP>/actuator/health/liveness
```

## 18. ถ้าอยากเปิด HTTPS ภายหลัง

pipeline รองรับไว้แล้วผ่าน parameter:

- `INSTALL_INGRESS_STACK`
- `ENABLE_INGRESS_TLS`
- `INGRESS_HOST`
- `TLS_SECRET_NAME`
- `CLUSTER_ISSUER_NAME`
- `LETSENCRYPT_EMAIL`

ต้องมีเพิ่ม:

- DNS host จริง
- ingress-nginx
- cert-manager

## 19. ปัญหาที่เจอบ่อย

### 19.1 `ImagePullBackOff`

เช็ก:

```bash
kubectl -n users-api describe pod -l app=users-api
```

สาเหตุที่เจอบ่อย:

- AKS pull image จาก ACR ไม่ได้
- service principal ไม่มีสิทธิ์ push image
- AKS ยังไม่ได้ผูกกับ ACR
- image tag ไม่ถูก

### 19.2 `CrashLoopBackOff`

เช็ก:

```bash
kubectl -n users-api logs deployment/users-api --all-containers=true --tail=200
```

สาเหตุที่เจอบ่อย:

- app start ไม่ขึ้น
- health probe ไม่ผ่าน
- image architecture ไม่ตรง node

### 19.3 `rollout status` timeout

เช็ก:

```bash
kubectl -n users-api rollout status deployment/users-api --timeout=180s
kubectl -n users-api get pods -o wide
kubectl -n users-api describe pod -l app=users-api
```

### 19.4 `az aks get-credentials` ไม่ผ่าน

สาเหตุที่เจอบ่อย:

- service principal ไม่มี role `Azure Kubernetes Service Cluster User Role`
- subscription ใน Jenkins ไม่ถูก
- ชื่อ resource group หรือ cluster ผิด

### 19.5 `az acr login` หรือ push image ไม่ผ่าน

สาเหตุที่เจอบ่อย:

- service principal ไม่มี role `AcrPush`
- ใช้ ACR name หรือ login server ผิด

## 20. คำสั่งที่ใช้บ่อย

ดู ACR repositories:

```bash
az acr repository list -n usersapiacr -o table
```

ดู image tags:

```bash
az acr repository show-tags -n usersapiacr --repository users-api -o table
```

เช็กว่า AKS pull ACR ได้:

```bash
az aks check-acr -g rg-users-api -n aks-users-api --acr usersapiacr.azurecr.io
```

ดู service:

```bash
kubectl -n users-api get svc users-api -o wide
```

scale ลงชั่วคราว:

```bash
kubectl -n users-api scale deploy users-api --replicas=0
```

scale กลับ:

```bash
kubectl -n users-api scale deploy users-api --replicas=2
kubectl -n users-api rollout status deployment/users-api --timeout=180s
```

## 21. สรุปสั้นที่สุด

สิ่งที่ต้องสร้างมี 5 อย่าง:

1. `rg-users-api`
2. `usersapiacr`
3. `aks-users-api`
4. `jenkins-linux-agent`
5. `jenkins-users-api-sp`

สิทธิ์ที่ต้อง add ให้ Jenkins service principal:

1. `AcrPush` บน ACR
2. `Azure Kubernetes Service Cluster User Role` บน AKS

เมื่อสร้างครบแล้ว:

1. ติดตั้ง Java, Docker, Azure CLI, kubectl บน VM
2. ผูก VM เข้า Jenkins เป็น agent
3. ใส่ Jenkins credentials
4. รัน pipeline

ประโยคเดียวจบ:

`Jenkins ใช้ service principal login Azure -> build image -> push ไป ACR -> ดึง AKS credentials -> apply Kubernetes manifests -> app รันบน AKS`
