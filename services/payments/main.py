from __future__ import annotations

import asyncio
import logging

from fastapi import FastAPI, Header
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from pydantic import BaseModel

from shared.telemetry import configure_json_logging, instrument_http_metrics, log_event, setup_telemetry

tracer, request_counter, error_counter, duration_histogram = setup_telemetry("payments")
configure_json_logging()
logger = logging.getLogger("payments")
app = FastAPI(title="RootLens Lab Payments", version="1.0.0")
FastAPIInstrumentor.instrument_app(app)
instrument_http_metrics(app, request_counter, error_counter, duration_histogram)
captures: dict[str, list[dict[str, str | float]]] = {}


class CaptureRequest(BaseModel):
    order_id: str
    amount: float


@app.get("/health")
async def health() -> dict[str, str]:
    return {"service": "payments", "status": "ok"}


@app.get("/debug/captures/{order_id}")
async def get_captures(order_id: str) -> dict[str, object]:
    return {"order_id": order_id, "count": len(captures.get(order_id, [])), "captures": captures.get(order_id, [])}


@app.post("/capture")
async def capture(
    payload: CaptureRequest,
    x_demo_fault: str | None = Header(default=None),
    x_attempt: str = Header(default="1"),
    idempotency_key: str | None = Header(default=None),
):
    with tracer.start_as_current_span("payments.capture") as span:
        span.set_attribute("order.id", payload.order_id)
        span.set_attribute("payment.attempt", int(x_attempt))
        span.set_attribute("payment.idempotency_key", idempotency_key or payload.order_id)
        key = idempotency_key or payload.order_id
        already_captured = any(entry["idempotency_key"] == key for entry in captures.get(payload.order_id, []))
        if already_captured:
            log_event(logger, "payment_idempotency_replay", order_id=payload.order_id, idempotency_key=key)
            return {"status": "already_captured", "capture_count": len(captures[payload.order_id])}

        capture_id = f"cap-{len(captures.get(payload.order_id, [])) + 1}"
        captures.setdefault(payload.order_id, []).append(
            {"capture_id": capture_id, "amount": payload.amount, "idempotency_key": key}
        )
        log_event(logger, "payment_captured", order_id=payload.order_id, capture_id=capture_id, amount=payload.amount)
        # Simulate a successful capture whose response is lost to a short client timeout.
        if x_demo_fault == "payment_retry_bug" and x_attempt == "1":
            await asyncio.sleep(0.8)
        return {"status": "captured", "capture_id": capture_id, "capture_count": len(captures[payload.order_id])}

