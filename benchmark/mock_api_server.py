"""
mock_api_server.py
==================
Mock HTTP server for BPMNFlow pizza-delivery-c7 API activities.

Endpoints
---------
POST /v1/authorize          → SC-PMT_AUTH  (https://api.pagamentos.com/v1/authorize)
POST /v1/deliveries         → DL-TRK_CREATE (https://api.logistica.com/v1/deliveries)

Response field names match the outputParameter names defined in pizza-delivery-c7.bpmn,
which are used as variableNames by SpringApiHandlerProvider to persist instance variables:

  SC-PMT_AUTH   → pagamento_txn_id, pagamento_status, pagamento_authorized_at
  DL-TRK_CREATE → tracking_id, tempo_estimado_entrega, entregador_nome
"""

import uuid
import random
import logging
from datetime import datetime, timezone
from flask import Flask, request, jsonify

app = Flask(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

def _parse_body(req):
    body = req.get_json(silent=True, force=True)
    return body or {}

# ---------------------------------------------------------------------------
# SC-PMT_AUTH — POST /v1/authorize
# Response fields match connector.output.* variableNames in the BPMN:
#   pagamento_txn_id, pagamento_status, pagamento_authorized_at
# ---------------------------------------------------------------------------

@app.route("/v1/authorize", methods=["POST"])
def authorize_payment():
    auth = request.headers.get("Authorization", "<absent>")
    body = _parse_body(request)
    log.info("→ POST /v1/authorize  from %s  Authorization: %s", request.remote_addr, auth)

    if random.random() < 0.10:
        log.info("  ✗ Payment declined (simulated)")
        return jsonify({
            "pagamento_txn_id":       None,
            "pagamento_status":       "DECLINED",
            "pagamento_authorized_at": None,
        }), 200

    txn_id       = f"TXN-{uuid.uuid4().hex[:12].upper()}"
    authorized_at = datetime.now(timezone.utc).isoformat()

    response = {
        "pagamento_txn_id":        txn_id,
        "pagamento_status":        "APPROVED",
        "pagamento_authorized_at": authorized_at,
    }

    log.info("  ✓ Authorized  customer=%s  amount=%s  txn=%s",
             body.get("customer_id", "?"), body.get("amount", "?"), txn_id)

    return jsonify(response), 200


# ---------------------------------------------------------------------------
# DL-TRK_CREATE — POST /v1/deliveries
# Response fields match connector.output.* variableNames in the BPMN:
#   tracking_id, tempo_estimado_entrega, entregador_nome
# ---------------------------------------------------------------------------

DRIVERS = ["Carlos Silva", "Ana Oliveira", "Pedro Santos", "Mariana Costa"]

@app.route("/v1/deliveries", methods=["POST"])
def create_delivery():
    auth = request.headers.get("Authorization", "<absent>")
    body = _parse_body(request)
    log.info("→ POST /v1/deliveries  from %s  Authorization: %s", request.remote_addr, auth)

    tracking_id = f"TRK-{uuid.uuid4().hex[:10].upper()}"
    eta_minutes = random.randint(25, 55)
    driver_name = random.choice(DRIVERS)

    response = {
        "tracking_id":            tracking_id,
        "tempo_estimado_entrega": eta_minutes,
        "entregador_nome":        driver_name,
    }

    log.info("  ✓ Delivery created  order=%s  tracking=%s  driver=%s  eta=%dmin",
             body.get("order_id", "?"), tracking_id, driver_name, eta_minutes)

    return jsonify(response), 200


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------

@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "endpoints": [
        "POST /v1/authorize",
        "POST /v1/deliveries",
    ]}), 200


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    print("""
╔══════════════════════════════════════════════════════════╗
║          BPMNFlow API Mock Server — pizza-delivery-c7    ║
╠══════════════════════════════════════════════════════════╣
║  POST http://localhost:9090/v1/authorize                 ║
║  POST http://localhost:9090/v1/deliveries                ║
║  GET  http://localhost:9090/health                       ║
╚══════════════════════════════════════════════════════════╝
""")
    app.run(host="0.0.0.0", port=9090, debug=True)