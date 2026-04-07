#!/bin/bash
# start-services.sh — รันเมื่อต้องการใช้ Grafana
# Jenkins agent ไม่ต้องรันที่นี่ — ใช้ systemd แทน (auto-start ตอน boot)
# PostgreSQL — ใช้ SSH tunnel แทน:
#   ssh -i <key> -L 5433:localhost:5433 azureuser@<VM_IP> \
#     "kubectl -n users-api port-forward svc/postgres 5433:5432"

echo "=== Starting port-forwards ==="

# Grafana port-forward (background)
echo "Starting Grafana port-forward on :3000..."
kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80 &
GRAFANA_PID=$!

echo ""
echo "=== Port-forwards started ==="
echo "Grafana  : http://localhost:3000  (PID $GRAFANA_PID)"
echo ""
echo "To stop: kill $GRAFANA_PID"
echo ""
echo "Press Ctrl+C to stop"
wait
