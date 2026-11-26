# KFX Terminator Pro

Contrôleur MIDI USB Android pour Cakewalk, fait pour Kooyu : 12 potards × 2 banks = 24 CC assignables (CC 20 à 43, canal 1), style néon ou réaliste.

## Télécharger l'APK

**[⬇ KFX.apk (dernière version)](https://github.com/perezsebastien15-dotcom/KFX-Mini-Studio-Laser-Terminator/releases/latest/download/KFX.apk)**

Installation : ouvrir le fichier sur la tablette, autoriser « sources inconnues ».

## Utilisation

1. Brancher la tablette au PC en USB, notification USB → **MIDI**.
2. Le PC voit « Android USB Peripheral Port » (rien à installer). Les néons du titre s'allument quand le MIDI est connecté.
3. Cakewalk : Préférences → MIDI → Devices → cocher le port en entrée, puis assigner les potards.
4. Potards :
   - double tap = réglages : nom, CC, couleur (rose, cyan, jaune, vert, orange, rouge, bleu, violet, éteint), type knob ou switch ;
   - knob : tourner le doigt autour = valeur (sens horaire = monte) ;
   - switch : tap = OFF (0, couleur éteint) ↔ ON (127, couleur choisie).
5. Ovale du milieu = nom du preset : tap = choisir un preset (chargé direct), double tap = menu 💾 enregistrer sous un nom (même nom = écrase) / 🎨 thème néon ou réaliste.
   Une config garde noms, CC, couleurs et positions ; au chargement les positions ne sont pas envoyées au PC.
6. Switch à gauche de chaque ligne = 2 banks par ligne, 24 CC assignables (témoin éteint = bank A, allumé = bank B). Rien n'est envoyé en MIDI à la bascule.
   - Ligne 1 : bank A = CC 20-25, bank B = CC 32-37. Ligne 2 : bank A = CC 26-31, bank B = CC 38-43.
   - Chaque CC garde son nom, sa couleur, son type et sa valeur (24 mémoires indépendantes).
7. Tout est sauvegardé à la fermeture.

## Publier une nouvelle version

Onglet **Actions** → **Release** → **Run workflow**. L'APK part dans une Release, le lien ci-dessus pointe toujours sur la dernière.
