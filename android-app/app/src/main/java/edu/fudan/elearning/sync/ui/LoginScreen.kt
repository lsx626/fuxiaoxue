package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R

@Composable
fun LoginScreen(
    state: LoginState,
    initialUsername: String = "",
    onOffline: (() -> Unit)? = null,
    onLogin: (String, String, Boolean) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    // Passwords are deliberately excluded from saved instance state.
    var password by remember { mutableStateOf("") }
    var rememberPassword by rememberSaveable { mutableStateOf(true) }
    var reveal by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val loading = state is LoginState.Loading
    val canLogin = !loading && username.isNotBlank() && password.isNotEmpty()
    fun submit() {
        if (!canLogin) return
        focus.clearFocus()
        onLogin(username.trim(), password, rememberPassword)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Image(
                            painterResource(R.drawable.ic_school),
                            null,
                            Modifier.padding(13.dp).size(28.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                        )
                    }
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            stringResource(R.string.login_kicker),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(
                    Modifier.padding(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.login_heading),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        stringResource(R.string.login_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    username,
                    { username = it },
                    label = { Text(stringResource(R.string.login_account)) },
                    enabled = !loading,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                    keyboardActions =
                        KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(stringResource(R.string.login_password)) },
                    enabled = !loading,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    visualTransformation =
                        if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { reveal = !reveal }) {
                            Text(
                                stringResource(
                                    if (reveal) R.string.login_hide_password
                                    else R.string.login_show_password
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            rememberPassword,
                            enabled = !loading,
                            role = Role.Checkbox,
                            onValueChange = { rememberPassword = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(rememberPassword, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            stringResource(R.string.login_remember),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.login_remember_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                (state as? LoginState.Error)?.let { Notice(it.message, error = true) }
                Button(
                    onClick = { submit() },
                    enabled = canLogin,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("login-submit"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (loading)
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    else Text(stringResource(R.string.login_action))
                }
                if (loading)
                    Text(
                        stringResource(R.string.login_connecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (onOffline != null)
                    TextButton(
                        onClick = onOffline,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.login_offline))
                    }
                Text(
                    stringResource(R.string.login_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
