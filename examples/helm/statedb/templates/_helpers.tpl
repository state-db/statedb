{{- define "statedb.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "statedb.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "statedb.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "statedb.labels" -}}
app.kubernetes.io/name: {{ include "statedb.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "statedb.selectorLabels" -}}
app.kubernetes.io/name: {{ include "statedb.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "statedb.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "statedb.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Secret holding the session encryption key: either the user's or ours. */}}
{{- define "statedb.sessionSecretName" -}}
{{- if .Values.sessionExistingSecret -}}
{{- .Values.sessionExistingSecret -}}
{{- else -}}
{{- printf "%s-session" (include "statedb.fullname" .) -}}
{{- end -}}
{{- end -}}

{{/* Releases upgraded with --reuse-values from before breakGlass existed have no such block. */}}
{{- define "statedb.breakGlassEnabled" -}}
{{- dig "enabled" true (.Values.breakGlass | default dict) -}}
{{- end -}}

{{- define "statedb.breakGlassExistingSecret" -}}
{{- dig "existingSecret" "" (.Values.breakGlass | default dict) -}}
{{- end -}}

{{- define "statedb.breakGlassGenerated" -}}
{{- and (eq (include "statedb.breakGlassEnabled" .) "true") (not (include "statedb.breakGlassExistingSecret" .)) -}}
{{- end -}}

{{/* Whether the generated break-glass Secret already holds both keys, so upgrades reuse them. */}}
{{- define "statedb.breakGlassReusable" -}}
{{- $data := (lookup "v1" "Secret" .Release.Namespace (include "statedb.breakGlassSecretName" .)).data | default dict -}}
{{- if and $data.password (index $data "password-hash") -}}true{{- else -}}false{{- end -}}
{{- end -}}

{{/* Changes only when the hash is regenerated; env from a Secret is read at container start. */}}
{{- define "statedb.breakGlassRevision" -}}
{{- if eq (include "statedb.breakGlassReusable" .) "true" -}}
{{- index (lookup "v1" "Secret" .Release.Namespace (include "statedb.breakGlassSecretName" .)).data "password-hash" | sha256sum | trunc 16 -}}
{{- else -}}
{{- now | unixEpoch -}}
{{- end -}}
{{- end -}}

{{/* Secret holding the break-glass password hash: either the user's or ours. */}}
{{- define "statedb.breakGlassSecretName" -}}
{{- if include "statedb.breakGlassExistingSecret" . -}}
{{- include "statedb.breakGlassExistingSecret" . -}}
{{- else -}}
{{- printf "%s-break-glass" (include "statedb.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "statedb.breakGlassSecretKey" -}}
{{- if include "statedb.breakGlassExistingSecret" . -}}
{{- dig "existingSecretPasswordHashKey" "password-hash" (.Values.breakGlass | default dict) -}}
{{- else -}}
password-hash
{{- end -}}
{{- end -}}

{{- define "statedb.sessionSecretKey" -}}
{{- if .Values.sessionExistingSecret -}}
{{- .Values.sessionExistingSecretKey -}}
{{- else -}}
session-encryption-secret
{{- end -}}
{{- end -}}
