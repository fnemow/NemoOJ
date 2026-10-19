from nemo_agent.config import Settings


def test_mock_model_only_enables_in_non_production():
    assert Settings(environment="test", mock_enabled=True).model_enabled
    assert not Settings(environment="production", mock_enabled=True).model_enabled
    assert not Settings(environment="test").model_enabled
