package com.melvin.minige.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.util.CustomerClassification
import com.melvin.minige.util.DeliveryVoiceParser

enum class DeliveryFilterChip(val label: String) {
    ALL("All"),
    OUTLET("Outlet"),
    RESIDENTIAL("Residential"),
    PAID("Paid"),
    PAUTANG("Pautang"),
    BANK("Bank"),
}

object DeliveryFilterHelper {

    fun matches(
        delivery: DeliveryRecord,
        searchQuery: String,
        selectedChip: DeliveryFilterChip,
    ): Boolean {
        val q = searchQuery.trim()
        val matchesText = q.isEmpty() ||
            delivery.customerName.contains(q, ignoreCase = true) ||
            delivery.notes.contains(q, ignoreCase = true)
        if (!matchesText) return false

        val classification = DeliveryVoiceParser.extractClassification(delivery.notes)
        return when (selectedChip) {
            DeliveryFilterChip.ALL -> true
            DeliveryFilterChip.OUTLET -> classification == CustomerClassification.OUTLET
            DeliveryFilterChip.RESIDENTIAL -> classification == CustomerClassification.RESIDENTIAL
            DeliveryFilterChip.PAID -> delivery.paymentType == PaymentType.CASH ||
                delivery.paymentType == PaymentType.BANK_DEPOSIT ||
                (delivery.paymentType == PaymentType.PAUTANG && delivery.isUtangFullyPaid)
            DeliveryFilterChip.PAUTANG -> delivery.paymentType == PaymentType.PAUTANG &&
                !delivery.isUtangFullyPaid
            DeliveryFilterChip.BANK -> delivery.paymentType == PaymentType.BANK_DEPOSIT ||
                delivery.notes.contains("[Bank Transfer]", ignoreCase = true) ||
                delivery.notes.contains("[Bank Deposit]", ignoreCase = true)
        }
    }

    fun filterDeliveries(
        deliveries: List<DeliveryRecord>,
        searchQuery: String,
        selectedChip: DeliveryFilterChip,
    ): List<DeliveryRecord> {
        return deliveries.filter { matches(it, searchQuery, selectedChip) }
    }
}

@Composable
fun DeliveryFilterBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedChip: DeliveryFilterChip,
    onSelectChip: (DeliveryFilterChip) -> Unit,
    modifier: Modifier = Modifier,
    chips: List<DeliveryFilterChip> = DeliveryFilterChip.entries,
    placeholderText: String = "Search customer or area...",
    testTagPrefix: String = "delivery_filter",
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { onSearchQueryChange(it.take(80)) },
            placeholder = { Text(placeholderText) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("${testTagPrefix}_clear_search"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = "Clear search",
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${testTagPrefix}_search_input"),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(chips, key = { it.name }) { chip ->
                FilterChip(
                    selected = selectedChip == chip,
                    onClick = {
                        onSelectChip(
                            if (selectedChip == chip && chip != DeliveryFilterChip.ALL) {
                                DeliveryFilterChip.ALL
                            } else {
                                chip
                            }
                        )
                    },
                    label = { Text(chip.label) },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("${testTagPrefix}_chip_${chip.name}"),
                )
            }
        }
    }
}
