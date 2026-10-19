"""Finite diagnostic labels; never serialize provider exceptions or generated code."""
import logging
import time
from contextvars import ContextVar
from contextlib import asynccontextmanager

log = logging.getLogger('nemo_agent.generation')
generation_phase = ContextVar('generation_phase', default='OTHER')


class GenerationFailure(ValueError):
    def __init__(self, category, phase):
        self.category, self.phase = category, phase
        super().__init__(f'PRIVATE_VALIDATION_FAILED:{category}')


@asynccontextmanager
async def phase(name):
    token = generation_phase.set(name)
    started = time.monotonic()
    outcome = 'SUCCEEDED'
    try:
        yield
    except BaseException:
        outcome = 'FAILED'
        raise
    finally:
        from nemo_agent.model_calls import active_recorder
        recorder = active_recorder.get()
        log.info('generation_phase task_id=%s phase=%s outcome=%s elapsed_ms=%d',
                 recorder.task_id if recorder else 'test', name, outcome,
                 round((time.monotonic()-started)*1000))
        generation_phase.reset(token)
