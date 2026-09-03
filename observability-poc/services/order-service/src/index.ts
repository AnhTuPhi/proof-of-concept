import express, { Request, Response } from 'express';
import axios from 'axios';
import { randomUUID } from 'crypto';
import { trace, metrics, SpanStatusCode, context } from '@opentelemetry/api';
import { logger } from './logger';

const PORT = Number(process.env.PORT ?? 3001);
const PAYMENT_URL = process.env.PAYMENT_URL ?? 'http://localhost:3002';
const INVENTORY_URL = process.env.INVENTORY_URL ?? 'http://localhost:3003';

const tracer = trace.getTracer('order-service');
const meter = metrics.getMeter('order-service');

const ordersCreated = meter.createCounter('orders_created_total', {
  description: 'Number of orders processed, labelled by terminal status',
});

interface OrderRequest {
  sku?: string;
  qty?: number;
  amount?: number;
}

const app = express();
app.use(express.json());

app.get('/health', (_req, res) => {
  res.json({ ok: true });
});

app.post('/orders', async (req: Request<unknown, unknown, OrderRequest>, res: Response) => {
  const { sku, qty, amount } = req.body ?? {};
  const orderId = `ord_${randomUUID().slice(0, 12)}`;
  const traceId = trace.getSpan(context.active())?.spanContext().traceId;

  if (!sku || !qty || !amount) {
    logger.warn({ orderId, sku, qty, amount }, 'rejected: missing fields');
    ordersCreated.add(1, { status: 'rejected' });
    return res.status(400).json({ error: 'sku, qty and amount are required' });
  }

  logger.info({ orderId, sku, qty, amount }, 'order received');

  try {
    const result = await tracer.startActiveSpan('order.process', async (span) => {
      span.setAttribute('order.id', orderId);
      span.setAttribute('order.sku', sku);
      span.setAttribute('order.qty', qty);

      const [reservation, charge] = await Promise.all([
        axios.post(`${INVENTORY_URL}/reserve`, { sku, qty, orderId }, { timeout: 5_000 }),
        axios.post(`${PAYMENT_URL}/charge`, { amount, orderId }, { timeout: 5_000 }),
      ]);

      span.setAttribute('order.reservation_id', reservation.data.reservationId);
      span.setAttribute('order.charge_id', charge.data.chargeId);
      span.setStatus({ code: SpanStatusCode.OK });
      span.end();
      return { reservation: reservation.data, charge: charge.data };
    });

    logger.info({ orderId, ...result }, 'order confirmed');
    ordersCreated.add(1, { status: 'confirmed' });
    res.status(201).json({ orderId, status: 'confirmed', trace_id: traceId, ...result });
  } catch (err) {
    const message = err instanceof Error ? err.message : 'unknown';
    logger.error({ orderId, err: message }, 'order failed');
    ordersCreated.add(1, { status: 'failed' });
    res.status(502).json({ orderId, status: 'failed', error: message, trace_id: traceId });
  }
});

app.listen(PORT, () => {
  logger.info({ port: PORT }, 'order-service listening');
});
