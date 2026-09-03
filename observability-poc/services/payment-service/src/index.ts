import express, { Request, Response } from 'express';
import { randomUUID } from 'crypto';
import { trace, metrics, SpanStatusCode } from '@opentelemetry/api';
import { logger } from './logger';

const PORT = Number(process.env.PORT ?? 3002);

const tracer = trace.getTracer('payment-service');
const meter = metrics.getMeter('payment-service');

const chargeDuration = meter.createHistogram('payment_charge_duration_ms', {
  description: 'Latency of payment charge operations',
  unit: 'ms',
});
const chargeTotal = meter.createCounter('payment_charges_total', {
  description: 'Number of charge attempts by outcome',
});

interface ChargeRequest {
  amount?: number;
  orderId?: string;
}

const app = express();
app.use(express.json());

app.get('/health', (_req, res) => {
  res.json({ ok: true });
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

app.post('/charge', async (req: Request<unknown, unknown, ChargeRequest>, res: Response) => {
  const { amount, orderId } = req.body ?? {};

  if (typeof amount !== 'number' || amount <= 0) {
    logger.warn({ orderId, amount }, 'invalid amount');
    chargeTotal.add(1, { outcome: 'rejected' });
    return res.status(400).json({ error: 'amount must be a positive number' });
  }

  const start = Date.now();
  await tracer.startActiveSpan('payment.charge', async (span) => {
    span.setAttribute('payment.amount', amount);
    span.setAttribute('payment.order_id', orderId ?? 'unknown');

    // simulate gateway latency
    const gatewayLatency = 30 + Math.random() * 120;
    await sleep(gatewayLatency);
    span.addEvent('gateway responded', { 'gateway.latency_ms': gatewayLatency });

    // 10% of charges fail to demonstrate error traces
    const shouldFail = Math.random() < 0.1;
    const elapsed = Date.now() - start;

    if (shouldFail) {
      span.setStatus({ code: SpanStatusCode.ERROR, message: 'gateway declined' });
      span.recordException(new Error('gateway declined'));
      span.end();
      chargeDuration.record(elapsed, { outcome: 'failed' });
      chargeTotal.add(1, { outcome: 'failed' });
      logger.error({ orderId, amount, elapsed_ms: elapsed }, 'charge declined by gateway');
      return res.status(402).json({ error: 'gateway declined', orderId });
    }

    const chargeId = `ch_${randomUUID().slice(0, 12)}`;
    span.setAttribute('payment.charge_id', chargeId);
    span.setStatus({ code: SpanStatusCode.OK });
    span.end();
    chargeDuration.record(elapsed, { outcome: 'success' });
    chargeTotal.add(1, { outcome: 'success' });
    logger.info({ orderId, amount, chargeId, elapsed_ms: elapsed }, 'charge captured');
    res.status(201).json({ chargeId, amount, orderId });
  });
});

app.listen(PORT, () => {
  logger.info({ port: PORT }, 'payment-service listening');
});
