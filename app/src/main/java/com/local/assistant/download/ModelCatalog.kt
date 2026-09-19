package com.local.assistant.download

/**
 * @param sha256 The upstream content hash, taken from Hugging Face's `X-Linked-ETag`
 *   for the LFS object. Verified after download so a truncated or tampered file can
 *   never be handed to the engine.
 * @param sizeBytes Exact size, used both for progress and to sanity-check a resumed
 *   partial file before continuing it.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val blurb: String,
    val recommended: Boolean = false,
) {
    val sizeGb: Double get() = sizeBytes / 1_000_000_000.0
}

/**
 * The models this app knows how to run.
 *
 * All are LiteRT-LM bundles under Apache 2.0. The GPU-suffixed builds are the ones
 * worth using on a phone: on a current flagship the GPU backend is roughly six
 * times the prefill throughput of CPU and about a quarter of the peak RAM, because
 * weights live in GPU buffers instead of being staged through the heap.
 */
object ModelCatalog {

    val GEMMA_4_E4B_GPU = ModelSpec(
        id = "gemma-4-e4b-it-gpu",
        displayName = "Gemma 4 E4B",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/" +
            "resolve/main/gemma-4-E4B-it-gpu.litertlm",
        fileName = "gemma-4-E4B-it-gpu.litertlm",
        sizeBytes = 2_969_059_328L,
        sha256 = "4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff",
        blurb = "Best quality. ~4.5B effective parameters, tuned for mobile GPUs.",
        recommended = true,
    )

    val GEMMA_4_E2B_GPU = ModelSpec(
        id = "gemma-4-e2b-it-gpu",
        displayName = "Gemma 4 E2B",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
            "resolve/main/gemma-4-E2B-it-gpu.litertlm",
        fileName = "gemma-4-E2B-it-gpu.litertlm",
        sizeBytes = 2_008_432_640L,
        sha256 = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
        blurb = "Roughly twice the speed, noticeably weaker. Good for a fast mode.",
    )

    val GEMMA_4_E4B_CPU = ModelSpec(
        id = "gemma-4-e4b-it",
        displayName = "Gemma 4 E4B (CPU build)",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/" +
            "resolve/main/gemma-4-E4B-it.litertlm",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        blurb = "Only needed if the GPU backend will not initialise on this device.",
    )

    val all = listOf(GEMMA_4_E4B_GPU, GEMMA_4_E2B_GPU, GEMMA_4_E4B_CPU)

    val default = GEMMA_4_E4B_GPU

    fun byId(id: String?): ModelSpec = all.firstOrNull { it.id == id } ?: default
}
