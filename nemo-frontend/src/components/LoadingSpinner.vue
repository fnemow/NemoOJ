<template>
  <span class="loading-spinner" aria-hidden="true" />
</template>

<style scoped>
.loading-spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  flex: none;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: loading-spin .8s linear infinite;
}
@keyframes loading-spin { to { transform: rotate(360deg); } }
</style>
