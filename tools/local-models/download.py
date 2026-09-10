"""Explicit download command; the serving process itself never downloads weights."""

from huggingface_hub import snapshot_download

from model_registry import MODELS, model_path, verify_snapshot

if __name__ == "__main__":
    for kind, (name, revision) in MODELS.items():
        print(f"Downloading official {name} snapshot {revision}", flush=True)
        snapshot_download(
            repo_id=name,
            revision=revision,
            local_dir=model_path(kind),
            token=False,
            allow_patterns=[
                "config.json",
                "tokenizer.json",
                "tokenizer_config.json",
                "special_tokens_map.json",
                "vocab.txt",
                "sentencepiece.bpe.model",
                "model.safetensors",
                "README.md",
            ],
        )
        verify_snapshot(kind)
        print(f"Downloaded and checksum verified {kind}", flush=True)
