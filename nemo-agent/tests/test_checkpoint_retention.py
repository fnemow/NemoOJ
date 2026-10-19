from types import SimpleNamespace

import pytest

from nemo_agent import checkpoint_retention


@pytest.mark.asyncio
async def test_cleanup_uses_latest_thread_timestamp_and_deletes_entire_thread(monkeypatch):
    batches = [[("expired-task",)], []]
    statements = []
    deleted = []

    class Cursor:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return None

        async def execute(self, sql, params):
            statements.append((sql, params))

        async def fetchall(self):
            return batches.pop(0)

    class Connection:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return None

        def cursor(self):
            return Cursor()

    async def connect(_):
        return Connection()

    async def delete(thread_id):
        deleted.append(thread_id)

    monkeypatch.setattr(checkpoint_retention.psycopg.AsyncConnection, "connect", connect)
    count = await checkpoint_retention.purge_expired_checkpoints(
        SimpleNamespace(adelete_thread=delete), "postgresql://example", 30)
    assert count == 1
    assert deleted == ["expired-task"]
    assert len(statements) == 2
    assert "MAX((checkpoint->>'ts')::timestamptz)" in statements[0][0]
    assert statements[0][1] == (30,)


@pytest.mark.asyncio
async def test_cleanup_rejects_nonpositive_retention():
    with pytest.raises(ValueError):
        await checkpoint_retention.purge_expired_checkpoints(None, "", 0)
