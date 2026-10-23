# SkinHeads

Plugin Paper 1.21.11 qui génère, pour chaque joueur, un item ItemsAdder (tesson de poterie)
dont la texture est le **buste de son skin**, récupéré via l'API `mc-api.io`.

## Fonctionnement

- **Première connexion** : le joueur est enregistré dans `plugins/SkinHeads/players.yml`
  avec un `custom_model_data` unique (à partir de 25, puis 26, 27...). L'image est
  téléchargée en asynchrone dans
  `plugins/ItemsAdder/contents/amethyy/textures/items/skins/`.
- **Chaque nuit à minuit** (Europe/Paris) : les images manquantes sont générées, le fichier
  `items/skins.yml` est réécrit, puis le plugin exécute `iazip` en console (uniquement s'il y a du nouveau).
- **Dimanche soir à minuit** : en plus, tous les skins sont re-téléchargés et comparés (SHA-256)
  à l'image actuelle ; si différents, l'image est remplacée avant `iazip`.

## Installation

1. `mvn clean package`  -> `target/SkinHeads.jar`
2. Mettre le jar dans `plugins/`, démarrer le serveur, ajuster `plugins/SkinHeads/config.yml`.

## Utilisation des items dans un menu

L'id ItemsAdder d'un joueur est `amethyy:skin_<uuid sans tirets>`.
La commande `/skinheads info <joueur>` donne l'id, le custom_model_data, le chemin du PNG et son URL source.
`/skinheads link <joueur>` affiche uniquement le lien utilisé pour générer/télécharger son PNG.
`/skinheads give <cible> [profil]` donne l'item via `/iagive`.

## Commandes (permission `skinheads.admin`)

- `/skinheads reload` : recharge la config
- `/skinheads regen` : génère les images manquantes et lance `iazip`
- `/skinheads check` : comparaison forcée des skins, puis `iazip` si changement
- `/skinheads info <joueur>` / `/skinheads give <cible> [profil]`
- `/skinheads remake <pseudo>` : force la regénération de l'icône d'un joueur déjà enregistré
- `/skinheads emulate <pseudo>` : simule la première connexion d'un joueur (enregistrement + pré-génération de la texture)

## Points d'attention

- Les images ne deviennent visibles en jeu qu'après `iazip` (donc le lendemain à minuit,
  ou immédiatement avec `/skinheads regen`).
- L'API est appelée avec le pseudo (`api.use-name: true`), l'UUID n'étant pas supporté.
