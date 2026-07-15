# Serialization

Graph, view, and runtime JSON codecs emit UTF-8 JSON with explicit schema versions, defaults/nulls, recursively sorted object keys, deterministic list order, and locale-independent numbers. Decode returns success, malformed input, or unsupported-schema results; decoded documents also carry typed validation.

Library version and document schema version are independent. Unknown major schemas fail. Compatible minor additions use defaults and extension collections. Migration hooks run separately from normal decoding and must explicitly return a migrated JSON object.

Extensions represented by the public extension contract round-trip. Arbitrary unknown top-level fields are ignored rather than silently promoted into semantic extensions.
