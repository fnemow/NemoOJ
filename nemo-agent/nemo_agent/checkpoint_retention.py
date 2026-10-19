"""Remove old LangGraph state after its business task is long finished."""

import asyncio
import logging

import psycopg


log = logging.getLogger("nemo_agent.checkpoint_retention")


async def purge_expired_checkpoints(checkpointer, postgres_url: str, retention_days: int) -> int:
    """Delete complete threads by latest checkpoint age, in bounded batches.

    AI tasks have a minutes-long execution lease. Using the newest checkpoint
    timestamp avoids deleting an old checkpoint from a still-active thread.
    LangGraph's adelete_thread removes checkpoints, writes and blobs together.
    """
    if retention_days < 1:
        raise ValueError("AI_RETENTION_DAYS must be positive")
    deleted = 0
    async with await psycopg.AsyncConnection.connect(postgres_url) as conn:
        while True:
            async with conn.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT thread_id FROM checkpoints
                    GROUP BY thread_id
                    HAVING MAX((checkpoint->>'ts')::timestamptz)
                           < now() - (%s * interval '1 day')
                    LIMIT 100
                    """,
                    (retention_days,),
                )
                thread_ids = [row[0] for row in await cursor.fetchall()]
            if not thread_ids:
                return deleted
            for thread_id in thread_ids:
                await checkpointer.adelete_thread(thread_id)
                deleted += 1


async def retention_loop(checkpointer, postgres_url: str, retention_days: int) -> None:
    while True:
        try:
            deleted = await purge_expired_checkpoints(checkpointer, postgres_url, retention_days)
            if deleted:
                log.info("Removed %s expired LangGraph checkpoint threads", deleted)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("Checkpoint retention cleanup failed; will retry")
        await asyncio.sleep(24 * 60 * 60)
