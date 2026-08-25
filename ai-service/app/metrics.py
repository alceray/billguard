from prometheus_client import Counter, Histogram

CLASSIFICATION_REQUESTS = Counter(
    "billguard_ai_classification_requests_total",
    "Classification requests received",
    ("outcome",),
)
CLASSIFICATION_LATENCY = Histogram(
    "billguard_ai_classification_duration_seconds",
    "Classification request latency",
)
OLLAMA_ERRORS = Counter(
    "billguard_ai_ollama_errors_total",
    "Ollama calls that failed or returned invalid output",
)
