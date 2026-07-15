# Security Architecture

See the repository [Security Policy](../SECURITY.md) for private reporting.

Treat graph, view, and runtime documents as untrusted data. Validate before attachment, bound consumer-provided document sizes at ingress, reject unsupported schemas, and do not render canonical source text unless the host has applied its own disclosure policy. This library performs no code execution, file access, source parsing, or network requests.
