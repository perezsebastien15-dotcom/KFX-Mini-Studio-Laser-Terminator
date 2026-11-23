# KFX — Kooyu FX Mini Studio Laser Terminator

Contrôleur MIDI USB Android pour Cakewalk, fait pour Kooyu : 12 potards × 2 banks = 24 CC assignables (CC 20 à 43, canal 1), style néon ou réaliste.

## Télécharger l'APK

**[⬇ KFX.apk (dernière version)](https://github.com/perezsebastien15-dotcom/KFX-Mini-Studio-Laser-Terminator/releases/latest/download/KFX.apk)**

Installation : ouvrir le fichier sur la tablette, autoriser « sources inconnues ».

## Utilisation

1. Brancher la tablette au PC en USB, notification USB → **MIDI**.
2. Le PC voit « Android USB Peripheral Port » (rien à installer).
3. Cakewalk : Préférences → MIDI → Devices → cocher le port en entrée, puis assigner les potards.
4. Potards :
   - tourner le doigt autour = valeur (sens horaire = monte) ;
   - tap = nom + CC ;
   - double tap sur le potard = couleur suivante (rose, cyan, jaune, vert, orange, rouge, bleu, violet) ;
   - double tap sur le nom = renommer.
5. Barre du milieu : ▼ = choisir une config (chargée direct), 💾 = enregistrer sous un nom (même nom = écrase), 🎨 = style néon / réaliste.
   Une config garde noms, CC, couleurs et positions ; au chargement les positions ne sont pas envoyées au PC.
6. Switch à gauche de chaque ligne = 2 banks par ligne, 24 CC assignables (témoin éteint = bank A, allumé = bank B). Rien n'est envoyé en MIDI à la bascule.
   - Ligne 1 : bank A = CC 20-25, bank B = CC 32-37. Ligne 2 : bank A = CC 26-31, bank B = CC 38-43.
   - Chaque CC garde sa propre valeur et sa couleur ; le nom est commun aux 2 banks d'un potard.
7. Tout est sauvegardé à la fermeture.

## Publier une nouvelle version

Onglet **Actions** → **Release** → **Run workflow**. L'APK part dans une Release, le lien ci-dessus pointe toujours sur la dernière.
