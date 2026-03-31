#!/bin/bash
# start-services.sh — รันเมื่อต้องการใช้ Grafana หรือ DBeaver
# Jenkins agent ไม่ต้องรันที่นี่ — ใช้ systemd แทน (auto-start ตอน boot)

echo "=== Starting port-forwards ==="

# Grafana port-forward (background)
echo "[1/2] Starting Grafana port-forward on :3000..."
kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80 &
GRAFANA_PID=$!

# PostgreSQL port-forward (background)
echo "[2/2] Starting PostgreSQL port-forward on :5433..."
kubectl -n users-api port-forward svc/postgres 5433:5432 &
POSTGRES_PID=$!

echo ""
echo "=== Port-forwards started ==="
echo "Grafana  : http://localhost:3000  (PID $GRAFANA_PID)"
echo "Postgres : localhost:5433         (PID $POSTGRES_PID)"
echo ""
echo "To stop: kill $GRAFANA_PID $POSTGRES_PID"
echo ""
echo "Press Ctrl+C to stop all port-forwards"
wait
