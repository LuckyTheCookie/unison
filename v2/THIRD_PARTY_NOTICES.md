# Composants tiers

Unison est distribué sous licence GNU GPL v3 (voir `../LICENSE`). Il embarque ou adapte les travaux suivants.

## Kawarp — fond animé

Le fond animé (`app/src/main/java/fr/lulu/unison/ui/kawarp/`) est un portage Android (AGSL / Jetpack Compose)
de [Kawarp](https://github.com/better-lyrics/kawarp) : même pipeline (teinte, flou de Kawase, déformation de domaine,
vignette, saturation, tramage) et mêmes réglages par défaut.

```
MIT License

Copyright (c) 2026 Better Lyrics

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Le bruit simplex utilisé par le shader vient de *webgl-noise* (Ian McEwan, Ashima Arts), également sous licence MIT.

## InnerTubeX — accès à YouTube Music

[InnerTubeX](https://github.com/MetrolistGroup/innertubex) (MetrolistGroup), licence GNU GPL v3. Utilisé comme dépendance, sans modification.

## Polices

- [Inter](https://github.com/rsms/inter) — Copyright 2020 The Inter Project Authors — SIL Open Font License 1.1.
- [Montserrat](https://github.com/JulietaUla/Montserrat) — Copyright 2024 The Montserrat.Git Project Authors — SIL Open Font License 1.1.

## Icônes

[MingCute](https://github.com/mingcute-design/mingcute-icons) — licence Apache 2.0. Les icônes sont converties en ressources vectorielles Android (`res/drawable/ic_*_line.xml`, `ic_*_fill.xml`).

## Paroles

Fournies par [LRCLIB](https://lrclib.net) et NetEase Cloud Music.
