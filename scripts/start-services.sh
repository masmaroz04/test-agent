#!/bin/bash
# start-services.sh — รันหลังจาก VM start ใหม่ทุกครั้ง

echo "=== Starting all services ==="

# 1. Grafana port-forward (background)
echo "[1/3] Starting Grafana port-forward on :3000..."
kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80 &
GRAFANA_PID=$!

# 2. PostgreSQL port-forward (background)
echo "[2/3] Starting PostgreSQL port-forward on :5433..."
kubectl -n users-api port-forward svc/postgres 5433:5432 &
POSTGRES_PID=$!

# 3. Jenkins agent
echo "[3/3] Starting Jenkins agent..."
cd ~/jenkins-agent
nohup java -jar agent.jar \
  -url https://nonsalable-jeannette-unleveled.ngrok-free.dev/ \
  -secret 2e42aa10230a6671d797673bdbc0009e55b7dbdd58a28f1b318e0b6a1267d97b \
  -name "linux-docker-agent" \
  -webSocket \
  -workDir "/home/azureuser/jenkins-agent" > agent.log 2>&1 &
AGENT_PID=$!

echo ""
echo "=== All services started ==="
echo "Grafana  : http://localhost:3000  (PID $GRAFANA_PID)"
echo "Postgres : localhost:5433         (PID $POSTGRES_PID)"
echo "Jenkins  : agent.log              (PID $AGENT_PID)"
echo ""
echo "To stop all: kill $GRAFANA_PID $POSTGRES_PID $AGENT_PID"
