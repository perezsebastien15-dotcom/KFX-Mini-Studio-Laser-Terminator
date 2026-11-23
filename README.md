# Neon Knobs

Contrôleur MIDI USB Android pour Cakewalk : 12 potards néon (CC 20 à 31, canal 1), style retro wave.

## Télécharger l'APK

**[⬇ NeonKnobs.apk (dernière version)](https://github.com/perezsebastien15-dotcom/NeonKnobs/releases/latest/download/NeonKnobs.apk)**

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
6. Switch à gauche de chaque ligne (témoin allumé = ON) : bascule les CC de la ligne, sans rien envoyer en MIDI.
   - Ligne 1 : OFF = CC 20-25, ON = CC 32-37. Ligne 2 : OFF = CC 26-31, ON = CC 38-43.
   - Chaque position a sa propre mémoire (nom, couleur, valeur) : 24 mémoires en tout, enregistrées aussi dans les configs.
7. Tout est sauvegardé à la fermeture.

## Publier une nouvelle version

Onglet **Actions** → **Release** → **Run workflow**. L'APK part dans une Release, le lien ci-dessus pointe toujours sur la dernière.
