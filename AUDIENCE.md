# AUDIENCE.md — Callback

## Primary audience: hackathon judges

The product is being built for a single moment: a 90-second demo and a
documentation pass by Qualcomm and Google engineers serving as judges
at the LiteRT on Snapdragon hackathon (Apr 30 – May 1, 2026, Google
office, hosted with Qualcomm and Samsung sponsorship).

Everything in the build is optimized to score well on five equally
weighted criteria:

1. **Must use LiteRT / LiteRT-LM compiled model API.** Non-negotiable.
   The build uses LiteRT-LM (Gemma-3n-E2B, EmbeddingGemma) and LiteRT
   (a classical detector from Qualcomm AI Hub) so both surfaces are
   exercised.

2. **Technological implementation.** Resource utilization, optimization,
   latency, performance, energy efficiency. Score driver: NPU
   utilization across a cascade of three models (detector, VLM,
   embedding) running on Hexagon, with ARCore tracking running in
   parallel on CPU/GPU so it does not compete for NPU cycles.

3. **Application use-case and innovation.** Problem-solving, creativity,
   uniqueness, UX. Score driver: visual memory on a phone with no cloud
   and no companion device. The judge places objects, the phone watches,
   the judge asks where something is, the phone points back to it.

4. **Deployment and accessibility.** Ease of installation and use.
   Score driver: single APK, sideload, no account, no setup wizard.
   First launch is functional within seconds.

5. **Presentation and documentation.** Clarity in the demo, code
   quality, repository hygiene. Score driver: a clean public GitHub
   repo with MIT license, a README a stranger can follow in four steps,
   inline comments on the model-loading paths, and a recorded demo
   video as a fallback.

## Secondary audience: any developer who reads the repo afterward

The repo will be public per submission rules. It should read like a
reference implementation, not like a hackathon hack. Anyone landing
on it should be able to understand the cascade architecture, see
which model runs on which engine, and reproduce the build.

## What we are not optimizing for

Not optimizing for a real consumer launch, not optimizing for
investor pitch material, not optimizing for the long-term Iris
narrative. The hackathon is its own thing.

## End-user framing for the demo narrative

The presenting teammate (Amogh) will pick the exact framing for the
on-stage story. The two viable framings:

- **Generic forgetfulness.** "Where did I put my keys" / "the phone
  remembers what you misplaced." Universally legible.
- **Specific user, specific story.** Memory-impaired user, ADHD,
  elderly relative, traveler in a hotel. More emotionally compelling,
  narrower.

Either works for the build; the build does not change. Amogh chooses
on Friday morning. The README and code are written generically so
either narrative is supported.

## Positioning (background context, not for the demo)

The category is no longer empty. Meta Ray-Ban, Google Gemini smart
glasses, HTC VIVE Eagle, Brilliant Labs Halo, and Mira (formerly
Halo, $6.6M seed Nov 2025) all ship or have announced wearable
spatial-memory features. Memories.ai, founded by ex-Meta researchers
behind the Ray-Ban AI stack, has $16M raised and an announced
partnership with Qualcomm to bring its Large Visual Memory Model
natively to Snapdragon processors starting later in 2026.

What none of them ship today: a fully on-device, phone-only, no-hub,
no-cloud version. That is the gap Callback fills for the duration
of the hackathon. The demo runs in airplane mode on the same Snapdragon
silicon that the official Qualcomm + Memories.ai consumer product is
targeting for 2026.

This positioning is **not** part of the demo or the README. Judges
who know the space will recognize it; bringing it up unprompted reads
as defensive. Mention only if a judge asks during Q&A.

## What "winning" looks like

A demo where:

- The judge places objects on a table within 30 seconds without
  rehearsal.
- The phone runs in airplane mode.
- Voice query yields a visible AR arrow plus spoken response in
  under 3 seconds.
- The on-screen NPU activity indicator shows non-trivial Hexagon
  utilization during the cascade.
- The repository link, when opened by a judge after the demo, builds
  and installs in under 5 minutes following the README.

If all five hold, the only thing that beats us is a team with the
same architecture and a more polished AR overlay. That is acceptable.
