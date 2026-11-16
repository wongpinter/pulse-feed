package com.simon.harmonichackernews.feed.classifier

/**
 * Fast, deterministic topic classifier for feed articles.
 * Matches explicit feed tags and curated domain keywords.
 */
object FeedTopicClassifier {
    val TOPICS = listOf(
        "AI",
        "Programming",
        "Security",
        "Science",
        "Business",
        "Design",
        "Hardware",
        "Privacy",
    )

    fun classify(
        title: String,
        content: String? = null,
        existingTags: List<String> = emptyList(),
    ): List<String> {
        val text = buildString {
            append(title)
            if (!content.isNullOrBlank()) {
                append(' ')
                append(content.take(600))
            }
        }.lowercase()

        val matched = mutableSetOf<String>()

        // 1. Map existing feed tags/categories
        for (tag in existingTags) {
            val lowerTag = tag.trim().lowercase()
            for (topic in TOPICS) {
                if (lowerTag == topic.lowercase() || lowerTag.contains(topic.lowercase())) {
                    matched += topic
                }
            }
        }

        // 2. High-signal domain keyword matching
        if (containsAny(text, "ai", "llm", "gpt", "machine learning", "deep learning", "neural", "openai", "anthropic", "claude", "gemini", "transformer", "diffusion", "weights", "agent")) {
            matched += "AI"
        }
        if (containsAny(text, "code", "programming", "developer", "kotlin", "rust", "python", "javascript", "typescript", "golang", "swift", "compiler", "api", "github", "git", "linux", "kernel", "sql", "postgres", "docker", "userspace", "algorithm")) {
            matched += "Programming"
        }
        if (containsAny(text, "security", "vulnerability", "cve", "malware", "exploit", "cryptography", "breach", "ssh", "enclave", "ransomware", "zero-day", "phishing", "auth", "token", "bioterrorism", "attack")) {
            matched += "Security"
        }
        if (containsAny(text, "privacy", "tracking", "surveillance", "gdpr", "fingerprinting", "telemetry", "adblock", "cookies", "data broker")) {
            matched += "Privacy"
        }
        if (containsAny(text, "science", "physics", "quantum", "astronomy", "biology", "nasa", "telescope", "fusion", "dna", "crispr", "research", "synthetic dna", "tuna", "species")) {
            matched += "Science"
        }
        if (containsAny(text, "business", "startup", "venture", "acquisition", "revenue", "ipo", "market", "economy", "investor", "valuation", "layoff", "pricing", "cost")) {
            matched += "Business"
        }
        if (containsAny(text, "design", "ui", "ux", "typography", "css", "frontend", "interface", "animation", "figma", "graphic")) {
            matched += "Design"
        }
        if (containsAny(text, "hardware", "cpu", "gpu", "chip", "semiconductor", "arm64", "risc-v", "nvidia", "intel", "amd", "electronics", "circuit", "silicon")) {
            matched += "Hardware"
        }

        return matched.toList()
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        for (keyword in keywords) {
            val regex = Regex("""\b${Regex.escape(keyword)}\b""", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(text)) return true
        }
        return false
    }
}
