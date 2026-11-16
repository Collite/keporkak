# The packaging check's shared fixture (S2.2 T2)

`scripts/render-agents.py --packaging-fixture agents/packaging/fixture` and kp-driver's reader
(`Packaging.problems`, `PackagingDataSpec`) read these files and must each report exactly the lines of
`expected.txt`: a `deny:` word and an MCP server a persona uses that the data does not map, and a placeholder nothing
fills — in a word's rule or in the writable set. Nothing here is a real persona, profile or packaging data.
