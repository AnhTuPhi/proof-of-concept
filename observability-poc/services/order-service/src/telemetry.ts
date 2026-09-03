import { NodeSDK } from '@opentelemetry/sdk-node';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-http';
import { OTLPLogExporter } from '@opentelemetry/exporter-logs-otlp-http';
import { PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
import { BatchLogRecordProcessor } from '@opentelemetry/sdk-logs';
import { getNodeAutoInstrumentations } from '@opentelemetry/auto-instrumentations-node';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  ATTR_SERVICE_NAME,
  ATTR_SERVICE_VERSION,
} from '@opentelemetry/semantic-conventions';
import { diag, DiagConsoleLogger, DiagLogLevel } from '@opentelemetry/api';

const otlp = process.env.OTEL_EXPORTER_OTLP_ENDPOINT ?? 'http://localhost:4318';

if (process.env.OTEL_LOG_LEVEL?.toLowerCase() === 'debug') {
  diag.setLogger(new DiagConsoleLogger(), DiagLogLevel.DEBUG);
}

const resource = resourceFromAttributes({
  [ATTR_SERVICE_NAME]: process.env.SERVICE_NAME ?? 'unknown-service',
  [ATTR_SERVICE_VERSION]: process.env.SERVICE_VERSION ?? '0.0.0',
  'deployment.environment': process.env.DEPLOYMENT_ENV ?? 'dev',
});

const sdk = new NodeSDK({
  resource,
  traceExporter: new OTLPTraceExporter({ url: `${otlp}/v1/traces` }),
  metricReader: new PeriodicExportingMetricReader({
    exporter: new OTLPMetricExporter({ url: `${otlp}/v1/metrics` }),
    exportIntervalMillis: 10_000,
  }),
  logRecordProcessors: [
    new BatchLogRecordProcessor(new OTLPLogExporter({ url: `${otlp}/v1/logs` })),
  ],
  instrumentations: [
    getNodeAutoInstrumentations({
      '@opentelemetry/instrumentation-fs': { enabled: false },
      '@opentelemetry/instrumentation-pino': {
        logKeys: { traceId: 'trace_id', spanId: 'span_id', traceFlags: 'trace_flags' },
      },
    }),
  ],
});

sdk.start();

process.on('SIGTERM', () => {
  sdk.shutdown().finally(() => process.exit(0));
});
