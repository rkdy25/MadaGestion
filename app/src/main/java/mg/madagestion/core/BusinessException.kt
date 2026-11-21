package mg.madagestion.core

/** Erreur « métier » : son message est affiché tel quel à l'utilisateur. */
class BusinessException(message: String) : Exception(message)
