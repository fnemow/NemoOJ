import asyncio
import logging

from nemo_agent.config import settings
from nemo_agent.worker import run

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
asyncio.run(run(settings))
