package com.bingwa.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class RepoAccessMode { ALL, SELECTED }

@Composable
fun GithubInstallLikeScreen() {
    var access by remember { mutableStateOf(RepoAccessMode.SELECTED) }
    val selectedRepos = remember { mutableStateListOf("examsgenerators/Bingwamobile005") }
    var repoMenu by remember { mutableStateOf(false) }
    val availableRepos = remember {
        listOf(
            "examsgenerators/Bingwamobile005",
            "examsgenerators/portal-web",
            "examsgenerators/infra"
        )
    }

    Column(
        Modifier.fillMaxSize()
            .background(C.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        HeroHeader()
        Spacer(Modifier.height(18.dp))
        SectionLabel("Overview")
        SummaryCard(
            primaryLabel = "Installation",
            primaryValue = "Trae-AI",
            secondaryLabel = "Repository scope",
            secondaryValue = if (access == RepoAccessMode.ALL) "All repositories" else "${selectedRepos.size} selected",
            tertiaryLabel = "Permissions",
            tertiaryValue = "Read + write",
            ctaLabel = "Review setup"
        )

        Spacer(Modifier.height(18.dp))
        SectionLabel("Activity")
        TwoColumnInfoRow()

        Spacer(Modifier.height(18.dp))
        SectionLabel("Application")
        AppCard(
            name = "Trae-AI",
            metaLeft = "Installed 2 days ago",
            metaMid = "Developed by Trae-AI",
            metaRight = "https://www.trae.ai"
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("Permissions")
        PermissionsCard(
            items = listOf(
                "Read access to actions, commit statuses, deployments, metadata, packages, and pages.",
                "Read and write access to checks, code, discussions, issues, pull requests, and workflows."
            )
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("Repository access")
        Surface(
            color = C.cardHi.copy(alpha = 0.96f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                C.cyan.copy(alpha = 0.08f),
                                C.cardHi,
                                C.card
                            )
                        )
                    )
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose where this installation can work", color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Keep access broad for convenience or restrict it to a small approved set of repositories.",
                            color = C.t2,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                    MetaPill(
                        label = if (access == RepoAccessMode.ALL) "Full scope" else "Scoped access",
                        tint = if (access == RepoAccessMode.ALL) C.blue else C.green
                    )
                }

                Spacer(Modifier.height(16.dp))
                RepoAccessOption(
                    title = "All repositories",
                    desc = "This applies to all current and future repositories owned by the resource owner. Also includes public repositories (read-only).",
                    selected = access == RepoAccessMode.ALL,
                    onSelect = { access = RepoAccessMode.ALL }
                )

                Spacer(Modifier.height(12.dp))
                Divider(color = C.w08)
                Spacer(Modifier.height(12.dp))

                RepoAccessOption(
                    title = "Only select repositories",
                    desc = "Select at least one repository. Also includes public repositories (read-only).",
                    selected = access == RepoAccessMode.SELECTED,
                    onSelect = { access = RepoAccessMode.SELECTED }
                )

                AnimatedVisibility(visible = access == RepoAccessMode.SELECTED) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MetaPill(label = "${selectedRepos.size} selected", tint = C.cyan)
                            OutlinedButton(
                                onClick = { repoMenu = true },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, C.border),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = C.cardHi),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Outlined.Folder, null, tint = C.t2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Add repository", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(10.dp))
                                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = C.t2, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(
                                expanded = repoMenu,
                                onDismissRequest = { repoMenu = false },
                                modifier = Modifier.background(C.cardHi).clip(RoundedCornerShape(14.dp)).border(1.dp, C.border, RoundedCornerShape(14.dp))
                            ) {
                                availableRepos.forEach { repo ->
                                    val alreadySelected = repo in selectedRepos
                                    DropdownMenuItem(
                                        text = { Text(repo, color = if (alreadySelected) C.t3 else C.t1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(Icons.Outlined.Folder, null, tint = if (alreadySelected) C.t3 else C.t2, modifier = Modifier.size(16.dp)) },
                                        onClick = {
                                            if (!alreadySelected) selectedRepos.add(repo)
                                            repoMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text("Approved repositories", color = C.t3, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))

                        selectedRepos.forEach { repo ->
                            SelectedRepoRow(
                                repo = repo,
                                onRemove = { selectedRepos.remove(repo) }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        ActionBarCard()

        Spacer(Modifier.height(22.dp))
        DangerZone()
    }
}

@Composable
private fun HeroHeader() {
    Surface(
        color = C.cardHi.copy(alpha = 0.96f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.88f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            C.cyan.copy(alpha = 0.18f),
                            C.cardHi,
                            C.surface
                        )
                    )
                )
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetaPill(label = "Developer settings", tint = C.cyan)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = C.w04,
                        border = BorderStroke(1.dp, C.w08)
                    ) {
                        IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Outlined.MoreHoriz, null, tint = C.t2, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Polished installation access", color = C.t1, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Review permissions, scope repositories, and keep risky actions clearly separated.",
                        color = C.t2,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderStatChip(icon = Icons.Outlined.Security, label = "Secure")
                    HeaderStatChip(icon = Icons.Outlined.Insights, label = "Organized")
                    HeaderStatChip(icon = Icons.Outlined.Public, label = "Git ready")
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    primaryLabel: String,
    primaryValue: String,
    secondaryLabel: String,
    secondaryValue: String,
    tertiaryLabel: String,
    tertiaryValue: String,
    ctaLabel: String
) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.82f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryStat(label = primaryLabel, value = primaryValue, modifier = Modifier.weight(1f))
            SummaryStat(label = secondaryLabel, value = secondaryValue, modifier = Modifier.weight(1f))
            SummaryStat(label = tertiaryLabel, value = tertiaryValue, modifier = Modifier.weight(1f))
        }
        Divider(color = C.w08)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(ctaLabel, color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.ArrowForward, null, tint = C.cyan, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = C.t3, fontSize = 11.sp)
        Text(value, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TwoColumnInfoRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MenuRow(
            icon = Icons.Outlined.Security,
            title = "Security log",
            subtitle = "Review recent permission and policy events",
            modifier = Modifier.weight(1f)
        )
        MenuRow(
            icon = Icons.Outlined.Archive,
            title = "Sponsorship log",
            subtitle = "Track sponsored installs and grants",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HeaderStatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(C.w04)
            .border(1.dp, C.w08, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, null, tint = C.t2, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = C.t2, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MetaPill(label: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.24f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tint)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = C.t3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.82f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.w04)
                    .border(1.dp, C.w08, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = C.cyan, modifier = Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Open", color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.ArrowForward, null, tint = C.cyan, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun AppCard(name: String, metaLeft: String, metaMid: String, metaRight: String) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            C.surface.copy(alpha = 0.35f),
                            C.card
                        )
                    )
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(18.dp))
                        .background(C.w04)
                        .border(1.dp, C.border, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Code, null, tint = C.green, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = C.t1, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Connected developer installation", color = C.t2, fontSize = 12.sp)
                }
                MetaPill(label = "Active", tint = C.green)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetaChip(icon = Icons.Outlined.Shield, text = metaLeft, modifier = Modifier.weight(1f))
                MetaChip(icon = Icons.Outlined.Settings, text = metaMid, modifier = Modifier.weight(1f))
            }
            Surface(
                color = C.surface.copy(alpha = 0.58f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.65f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Link, null, tint = C.t2, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(metaRight, color = C.cyan, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ArrowForward, null, tint = C.t2, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun MetaChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(C.w04)
            .border(1.dp, C.w08, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(icon, null, tint = C.t2, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = C.t2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = C.t1, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PermissionsCard(items: List<String>) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            items.forEachIndexed { idx, s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(C.surface.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Outlined.TaskAlt, null, tint = C.green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(s, color = C.t2, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (idx != items.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun RepoAccessOption(title: String, desc: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        color = if (selected) C.cyan.copy(alpha = 0.08f) else C.surface.copy(alpha = 0.44f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (selected) C.cyan.copy(alpha = 0.35f) else C.border.copy(alpha = 0.72f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onSelect() }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = C.cyan, unselectedColor = C.t3)
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.padding(top = 2.dp)) {
                Text(title, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(desc, color = C.t3, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun SelectedRepoRow(repo: String, onRemove: () -> Unit) {
    Surface(
        color = C.surface.copy(alpha = 0.58f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Folder, null, tint = C.t2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(repo, color = C.t1, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = C.t3, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ActionBarCard() {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.82f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Actions", color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Save the current configuration or cancel and keep the existing installation permissions unchanged.",
                color = C.t2,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {},
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.green, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text("Save changes", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, C.border),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = C.surface, contentColor = C.t1)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DangerZone() {
    Column {
        Text("Danger zone", color = C.red, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Surface(
            color = C.card,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, C.red.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = C.red, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Suspend this installation", color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("This blocks the app from accessing your connected repositories and developer resources.", color = C.t2, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, C.red.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = C.redDim,
                        contentColor = C.red
                    )
                ) {
                    Text("Suspend installation", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
