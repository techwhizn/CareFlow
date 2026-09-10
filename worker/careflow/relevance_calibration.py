"""Reproducible threshold selection from explicit labels, never inferred labels."""

import math


def calibrate(samples, minimum_precision=0.95):
    if type(minimum_precision) not in (int, float) or not 0 < minimum_precision <= 1:
        raise ValueError("minimum_precision must be in (0, 1]")
    if (
        not isinstance(samples, list)
        or not samples
        or any(
            not isinstance(row, dict)
            or type(row.get("relevant")) is not bool
            or type(row.get("score")) not in (int, float)
            or not math.isfinite(row["score"])
            for row in samples
        )
    ):
        raise ValueError(
            "Every sample requires an explicit boolean label and finite score"
        )
    positives = sum(row["relevant"] for row in samples)
    if positives == 0 or positives == len(samples):
        raise ValueError("Calibration requires both positive and negative labels")
    points = []
    for threshold in sorted({row["score"] for row in samples}):
        tp = sum(row["relevant"] and row["score"] >= threshold for row in samples)
        fp = sum(not row["relevant"] and row["score"] >= threshold for row in samples)
        fn, tn = positives - tp, len(samples) - positives - fp
        precision = tp / (tp + fp) if tp + fp else 0
        recall = tp / positives
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0
        points.append(
            {
                "threshold": threshold,
                "true_positive": tp,
                "false_positive": fp,
                "false_negative": fn,
                "true_negative": tn,
                "precision": precision,
                "recall": recall,
                "f1": f1,
            }
        )
    eligible = [row for row in points if row["precision"] >= minimum_precision]
    # Conservative threshold wins ties. Failure to meet the target is explicit.
    selected = (
        max(eligible, key=lambda row: (row["f1"], row["threshold"]))
        if eligible
        else None
    )
    return {
        "samples": len(samples),
        "positives": positives,
        "minimum_precision": minimum_precision,
        "selected": selected,
        "curve": points,
    }
