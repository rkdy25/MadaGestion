# Note de conception — MadaGestion

## Principes non négociables

- Cloisonnement multi-entreprises appliqué côté serveur et base de données.
- Toute opération porte un `company_id` et un UUID global.
- Montants et quantités stockés en décimaux exacts (`NUMERIC` côté PostgreSQL), jamais en flottants binaires.
- Documents validés non modifiables directement ; corrections par avoir, annulation encadrée ou contrepassation.
- Commande, réception/livraison, facture et règlement sont des objets distincts.
- Les paiements peuvent être partiels, multiples, répartis sur plusieurs factures ou rester en avances non affectées.
- Les traitements multi-modules doivent être transactionnels et idempotents.
- Le moteur comptable est configurable par entreprise ; PCG 2005 Madagascar est le profil initial, pas une contrainte codée en dur.

## Cycle commercial cible

### Achats
Demande d'achat → commande fournisseur → réception(s) partielle(s) → facture fournisseur → paiement(s).

### Ventes
Devis → commande client → livraison(s) partielle(s) → facture client → encaissement(s).

### Retours
Retours physiques et avoirs financiers sont liés mais distincts : un avoir ne doit pas provoquer un mouvement de stock sans retour physique correspondant.

## Contrôles à implémenter

- Quantités reçues/livrées ne dépassant pas les quantités autorisées, sauf dérogation permissionnée.
- Rapprochement commande-réception-facture configurable.
- Pas de double mouvement lors d'une synchronisation répétée.
- Pas de modification silencieuse des documents validés.
- Écritures comptables équilibrées, verrouillées après validation et traçables.
- Gestion des périodes comptables ouvertes/fermées.

## Choix de la version 0.3.0

- **Le compte collectif du tiers fait foi.** Le solde d'un client ou d'un fournisseur est lu dans les écritures validées du compte 411 / 401 ventilées par tiers : factures, avoirs, règlements, frais, ristournes et reprise d'ouverture y figurent tous. Aucun solde n'est stocké à part.
- **Frais et ristournes sans nouvelle table.** Chaque opération est une écriture d'opérations diverses marquée `PARTY_OP` ; elle s'annule par contrepassation. Le schéma de la base reste en version 1 : aucune migration.
- **Paiement au comptant atomique.** La validation de la facture et son règlement sont dans la même transaction : si la caisse est insuffisante, rien n'est enregistré.
- **Situation initiale.** Une écriture équilibrée au journal des à-nouveaux, marquée `OPENING`. Le stock y entre par article pour que quantités, CUMP et compte de stock restent d'accord.
- **Export Excel sans bibliothèque.** Un fichier .xlsx est une archive ZIP de fichiers XML : `XlsxWriter` l'écrit directement, sans alourdir l'APK.

