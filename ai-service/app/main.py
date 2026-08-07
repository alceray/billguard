from fastapi import FastAPI

from app.config import get_settings
from app.errors import install_exception_handlers
from app.logging import configure_logging
from app.routers import classify, health

settings = get_settings()
configure_logging(settings.log_level)

app = FastAPI(title="BillGuard AI Service", version="0.1.0", docs_url=None, redoc_url=None)
install_exception_handlers(app)
app.include_router(health.router)
app.include_router(classify.router)

