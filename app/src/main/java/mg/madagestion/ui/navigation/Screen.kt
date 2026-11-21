package mg.madagestion.ui.navigation

import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.party.PartyOperationKind

sealed interface Screen {
    data object Home : Screen
    data class Factures(val sale: Boolean) : Screen
    data class FactureEdit(val id: String?, val kind: InvoiceKind) : Screen
    data class FactureDetail(val id: String) : Screen
    data object Reglements : Screen
    data class ReglementNew(
        val direction: PaymentDirection,
        val invoiceId: String? = null,
        val thirdPartyId: String? = null
    ) : Screen
    data class ReglementDetail(val id: String) : Screen
    data object Stocks : Screen
    data class ArticleEdit(val id: String?) : Screen
    data class Tiers(val type: ThirdPartyType = ThirdPartyType.CUSTOMER) : Screen
    data class TiersEdit(val id: String?, val type: ThirdPartyType) : Screen
    data class TiersDetail(val id: String) : Screen
    data class TiersOperation(val thirdPartyId: String, val kind: PartyOperationKind) : Screen
    data object Compta : Screen
    data object SituationInitiale : Screen
    data class EcritureEdit(val id: String?) : Screen
    data class EcritureDetail(val id: String) : Screen
    data object Rapports : Screen
    data object Controles : Screen
    data object Reglages : Screen
}
