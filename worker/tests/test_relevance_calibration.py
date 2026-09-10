import pytest

from careflow.relevance_calibration import calibrate


def test_threshold_is_inclusive_and_precision_target_controls_selection():
    result = calibrate(
        [
            {"score": 0.9, "relevant": True},
            {"score": 0.8, "relevant": False},
            {"score": 0.7, "relevant": True},
            {"score": 0.1, "relevant": False},
        ],
        1.0,
    )
    selected = result["selected"]
    assert selected["threshold"] == 0.9
    assert selected["true_positive"] == 1 and selected["false_positive"] == 0
    assert selected["false_negative"] == 1 and selected["recall"] == 0.5
    assert (
        calibrate(
            [{"score": 0.9, "relevant": False}, {"score": 0.8, "relevant": True}], 1.0
        )["selected"]
        is None
    )


@pytest.mark.parametrize(
    "samples",
    [
        [],
        [None],
        {"score": 0.5, "relevant": True},
        [{"score": 0.8, "relevant": True}],
        [{"score": float("nan"), "relevant": False}],
        [{"score": None, "relevant": True}],
        [{"score": 0.5, "relevant": "yes"}],
    ],
)
def test_missing_labels_and_unknown_scores_are_not_guessed(samples):
    with pytest.raises(ValueError):
        calibrate(samples)
