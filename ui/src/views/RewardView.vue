<script lang="ts" setup>
import { axiosInstance } from "@halo-dev/api-client";
import { utils } from "@halo-dev/ui-shared";
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VPageHeader,
  VPagination,
  VSpace,
  VTag,
} from "@halo-dev/components";
import { computed, onMounted, ref, watch } from "vue";
import IconHandCoins from "~icons/lucide/hand-coins";
import RecordEditModal from "../components/RecordEditModal.vue";

const API_BASE = "/apis/console.api.reward.nanzhiy.cn/v1alpha1";

interface RewardRecord {
  metadata: { name: string; creationTimestamp?: string };
  spec: {
    amount?: number;
    method?: string;
    nickname?: string;
    message?: string;
    contact?: string;
    transactionNo?: string;
    avatar?: string;
    status?: string;
    /** 未填写时回落到 creationTimestamp，与后端 resolveRewardTime 保持同一口径。 */
    rewardTime?: string;
  };
}

const records = ref<RewardRecord[]>([]);
const loading = ref(false);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const statusFilter = ref("");
const keyword = ref("");
const selected = ref<string[]>([]);
const selectMode = ref(false);
const pendingTotal = ref(0);
const syncing = ref(false);
const currencySymbol = ref("¥");
const canManage = computed(() =>
  utils.permission.has(["plugin:plugin-reward:records:manage"])
);

const selectedRecord = ref<RewardRecord>();
const editVisible = ref(false);
const createVisible = ref(false);

const statusItems = [
  { label: "全部状态", value: "" },
  { label: "待确认", value: "PENDING" },
  { label: "已确认", value: "CONFIRMED" },
  { label: "已拒绝", value: "REJECTED" },
];

const allSelected = computed(
  () => records.value.length > 0 && selected.value.length === records.value.length
);

async function fetchRecords() {
  loading.value = true;
  try {
    const { data } = await axiosInstance.get(`${API_BASE}/records`, {
      params: {
        page: page.value,
        size: size.value,
        status: statusFilter.value || undefined,
        keyword: keyword.value || undefined,
      },
    });
    records.value = data.items ?? [];
    total.value = data.total ?? 0;
    selected.value = [];
    await fetchPendingCount();
  } catch {
    Toast.error("加载打赏记录失败");
  } finally {
    loading.value = false;
  }
}

async function fetchPendingCount() {
  try {
    const { data } = await axiosInstance.get(`${API_BASE}/records`, {
      params: { status: "PENDING", page: 1, size: 1 },
    });
    pendingTotal.value = data.total ?? 0;
  } catch {
    pendingTotal.value = 0;
  }
}

async function fetchCurrency() {
  try {
    const { data } = await axiosInstance.get(
      "/apis/api.reward.nanzhiy.cn/v1alpha1/public/config"
    );
    currencySymbol.value = data.currencySymbol || "¥";
  } catch {
    currencySymbol.value = "¥";
  }
}

function onSizeChange() {
  page.value = 1;
  fetchRecords();
}

function openEdit(record: RewardRecord) {
  if (!canManage.value) {
    return;
  }
  if (selectMode.value) {
    toggleOne(record.metadata.name);
    return;
  }
  selectedRecord.value = record;
  editVisible.value = true;
}

/**
 * VButton 声明的 click 事件不携带原生事件，`@click.stop` 拿不到 event 会直接抛错，
 * 既不执行处理函数也阻止不了冒泡；这里按点击目标判断，行内操作按钮带 reward-row-action，
 * 标签兜底用来应对 VButton 将来不再渲染成 button 的情况。
 */
function onEntityClick(record: RewardRecord, event: MouseEvent) {
  const target = event.target as HTMLElement | null;
  if (target?.closest(".reward-row-action, button, a, input")) {
    return;
  }
  openEdit(record);
}

function toggleSelectMode() {
  selectMode.value = !selectMode.value;
  selected.value = [];
}

function toggleAll() {
  if (allSelected.value) {
    selected.value = [];
    return;
  }
  selected.value = records.value.map((item) => item.metadata.name);
}

function toggleOne(name: string) {
  const index = selected.value.indexOf(name);
  if (index >= 0) {
    selected.value.splice(index, 1);
  } else {
    selected.value.push(name);
  }
}

async function changeStatus(record: RewardRecord, next: string) {
  try {
    await axiosInstance.put(`${API_BASE}/records/${record.metadata.name}/status`, { status: next });
    Toast.success(next === "CONFIRMED" ? "已确认，将出现在赞助者墙" : "已拒绝");
    await fetchRecords();
  } catch {
    Toast.error("操作失败");
  }
}

/**
 * 删除成功后立即本地摘除，避免紧接着重新拉列表把刚删的记录又带回来
 * （Halo 删除后索引由后台异步回收，此时查询仍可能命中已删条目）。
 * 当前页被删空且不是第一页时回退一页加载，其余情况等用户下次刷新对齐。
 */
function removeFromList(targets: RewardRecord[]) {
  const removed = new Set(targets.map((item) => item.metadata.name));
  const pendingRemoved = targets.filter((item) => item.spec.status === "PENDING").length;
  records.value = records.value.filter((item) => !removed.has(item.metadata.name));
  total.value = Math.max(total.value - targets.length, 0);
  pendingTotal.value = Math.max(pendingTotal.value - pendingRemoved, 0);
  selected.value = selected.value.filter((name) => !removed.has(name));
  if (records.value.length === 0 && page.value > 1) {
    page.value -= 1;
    fetchRecords();
  }
}

async function removeRecord(record: RewardRecord) {
  Dialog.warning({
    title: "是否确认删除该打赏记录？",
    description: "删除之后将无法恢复。",
    confirmType: "danger",
    onConfirm: async () => {
      try {
        await axiosInstance.delete(`${API_BASE}/records/${record.metadata.name}`);
        Toast.success("已删除");
        removeFromList([record]);
      } catch {
        Toast.error("删除失败");
      }
    },
  });
}

async function batchStatus(status: string) {
  if (!selected.value.length) {
    return;
  }
  const label = status === "CONFIRMED" ? "确认" : "拒绝";
  try {
    await axiosInstance.put(`${API_BASE}/records/batch-status`, {
      names: selected.value,
      status,
    });
    Toast.success(`已批量${label} ${selected.value.length} 条记录`);
    await fetchRecords();
  } catch {
    Toast.error(`批量${label}失败`);
  }
}

function batchDelete() {
  if (!selected.value.length) {
    return;
  }
  const targets = records.value.filter((item) => selected.value.includes(item.metadata.name));
  Dialog.warning({
    title: `是否确认删除选中的 ${targets.length} 条记录？`,
    description: "删除之后将无法恢复。",
    confirmType: "danger",
    onConfirm: async () => {
      try {
        await axiosInstance.post(`${API_BASE}/records/batch-delete`, {
          names: targets.map((item) => item.metadata.name),
        });
        Toast.success("批量删除成功");
        removeFromList(targets);
      } catch {
        Toast.error("批量删除失败");
      }
    },
  });
}

async function syncAfdian() {
  syncing.value = true;
  try {
    const { data } = await axiosInstance.post(`${API_BASE}/afdian/sync`);
    if (data.message) {
      Toast.warning(data.message);
    } else {
      Toast.success(`同步完成，新增 ${data.synced ?? 0} 条记录`);
    }
    await fetchRecords();
  } catch (error: any) {
    Toast.error(error?.response?.data?.detail || "同步失败");
  } finally {
    syncing.value = false;
  }
}

async function exportCsv() {
  try {
    const { data } = await axiosInstance.get(`${API_BASE}/records/export`, {
      responseType: "blob",
    });
    const url = window.URL.createObjectURL(data as Blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "reward-records.csv";
    link.click();
    window.URL.revokeObjectURL(url);
  } catch {
    Toast.error("导出失败");
  }
}

function formatAmount(amount?: number) {
  return amount === undefined || amount === null
    ? "-"
    : `${currencySymbol.value}${Number(amount).toFixed(2)}`;
}

function formatTime(timestamp?: string) {
  return timestamp ? new Date(timestamp).toLocaleString() : "-";
}

const METHOD_LABELS: Record<string, string> = {
  wechat: "微信",
  alipay: "支付宝",
  afdian: "爱发电",
};

/** 未知取值原样展示，避免把将来新增的支付方式误标成微信。 */
function methodLabel(value?: string) {
  return value ? (METHOD_LABELS[value] ?? value) : "-";
}

function statusLabel(value?: string) {
  if (value === "CONFIRMED") return "已确认";
  if (value === "REJECTED") return "已拒绝";
  return "待确认";
}

function statusTheme(value?: string): "default" | "primary" | "danger" {
  if (value === "CONFIRMED") return "primary";
  if (value === "REJECTED") return "danger";
  return "default";
}

watch(statusFilter, () => {
  page.value = 1;
  fetchRecords();
});

let searchTimer: number | undefined;
watch(keyword, () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => {
    page.value = 1;
    fetchRecords();
  }, 300);
});

onMounted(() => {
  fetchRecords();
  fetchCurrency();
});
</script>

<template>
  <VPageHeader title="打赏">
    <template #icon>
      <IconHandCoins />
    </template>
    <template #actions>
      <VSpace>
        <VButton v-if="canManage" type="primary" @click="createVisible = true">新增</VButton>
        <VButton v-if="canManage" type="secondary" :disabled="syncing" @click="syncAfdian">
          同步爱发电
        </VButton>
        <VButton v-if="canManage" type="secondary" @click="exportCsv">导出 CSV</VButton>
        <VButton type="secondary" :disabled="loading" @click="fetchRecords">刷新</VButton>
      </VSpace>
    </template>
  </VPageHeader>

  <div class="reward-page-wrap">
    <VAlert
      v-if="pendingTotal > 0"
      :title="`有 ${pendingTotal} 条待确认的打赏记录`"
      type="default"
      :closable="false"
      class="reward-alert"
    >
      <template #description>
        <button
          v-if="statusFilter !== 'PENDING'"
          type="button"
          class="reward-alert__link"
          @click="statusFilter = 'PENDING'"
        >
          查看并处理
        </button>
        <span v-else>当前已在查看待确认记录</span>
      </template>
    </VAlert>

    <VCard>
      <div class="reward-toolbar">
        <div class="reward-toolbar__left">
          <SearchInput v-model="keyword" placeholder="输入关键词搜索" />
          <FilterDropdown v-model="statusFilter" label="状态" :items="statusItems" />
          <FilterCleanButton v-if="statusFilter" @click="statusFilter = ''" />
        </div>
        <div class="reward-toolbar__right">
          <span class="reward-toolbar__total">共 {{ total }} 条</span>
          <VButton
            v-if="canManage"
            size="sm"
            :type="selectMode ? 'secondary' : 'default'"
            @click="toggleSelectMode"
          >
            {{ selectMode ? "退出批量" : "批量操作" }}
          </VButton>
        </div>
      </div>

      <div v-if="selectMode" class="reward-batch">
        <VSpace class="reward-batch__actions">
          <VButton size="sm" @click="toggleAll">{{ allSelected ? "取消全选" : "全选本页" }}</VButton>
          <VButton size="sm" :disabled="!selected.length" @click="selected = []">清空</VButton>
          <VButton size="sm" type="secondary" :disabled="!selected.length" @click="batchStatus('CONFIRMED')">
            批量确认
          </VButton>
          <VButton size="sm" type="secondary" :disabled="!selected.length" @click="batchStatus('REJECTED')">
            批量拒绝
          </VButton>
          <VButton size="sm" type="danger" :disabled="!selected.length" @click="batchDelete">批量删除</VButton>
        </VSpace>
        <span class="reward-toolbar__total">已选 {{ selected.length }} 条</span>
      </div>

      <VLoading v-if="loading" />

      <VEmpty
        v-else-if="records.length === 0"
        title="暂无打赏记录"
        message="访客在打赏页提交后会出现在这里，也可以点击右上角「新增」手动补录。"
      />

      <VEntityContainer v-else>
        <VEntity
          v-for="record in records"
          :key="record.metadata.name"
          class="reward-entity"
          :is-selected="selected.includes(record.metadata.name)"
          @click="onEntityClick(record, $event)"
        >
          <template v-if="selectMode" #checkbox>
            <input
              type="checkbox"
              :checked="selected.includes(record.metadata.name)"
              @click.stop="toggleOne(record.metadata.name)"
            />
          </template>
          <template #start>
            <div class="reward-item">
              <img
                v-if="record.spec.avatar"
                class="reward-avatar"
                :src="record.spec.avatar"
                alt=""
                loading="lazy"
              />
              <div class="reward-item__body">
                <div class="reward-item__name">{{ record.spec.nickname || "匿名" }}</div>
                <div class="reward-item__desc">{{ record.spec.message || "未填写留言" }}</div>
              </div>
            </div>
          </template>
          <template #end>
            <VEntityField>
              <template #description>
                <span class="reward-amount">{{ formatAmount(record.spec.amount) }}</span>
              </template>
            </VEntityField>
            <VEntityField :description="methodLabel(record.spec.method)" />
            <VEntityField
              :description="formatTime(record.spec.rewardTime || record.metadata.creationTimestamp)"
            />
            <VEntityField>
              <template #description>
                <VTag :theme="statusTheme(record.spec.status)">
                  {{ statusLabel(record.spec.status) }}
                </VTag>
              </template>
            </VEntityField>
            <VSpace>
              <VButton
                v-if="canManage && record.spec.status !== 'CONFIRMED'"
                class="reward-row-action"
                size="sm"
                type="secondary"
                @click="changeStatus(record, 'CONFIRMED')"
              >
                确认
              </VButton>
              <VButton
                v-if="canManage && record.spec.status !== 'REJECTED'"
                class="reward-row-action"
                size="sm"
                type="secondary"
                @click="changeStatus(record, 'REJECTED')"
              >
                拒绝
              </VButton>
              <VButton
                v-if="canManage"
                class="reward-row-action"
                size="sm"
                type="danger"
                @click="removeRecord(record)"
              >
                删除
              </VButton>
            </VSpace>
          </template>
        </VEntity>
      </VEntityContainer>

      <div v-if="records.length > 0" class="reward-pagination">
        <VPagination
          v-model:page="page"
          v-model:size="size"
          :total="total"
          :size-options="[20, 50, 100]"
          @update:page="fetchRecords"
          @update:size="onSizeChange"
        />
      </div>
    </VCard>
  </div>

  <RecordEditModal
    v-if="editVisible && selectedRecord"
    :record="selectedRecord"
    @close="editVisible = false"
    @saved="fetchRecords"
  />

  <RecordEditModal v-if="createVisible" @close="createVisible = false" @saved="fetchRecords" />
</template>

<style scoped>
.reward-page-wrap {
  padding: 1rem;
}

.reward-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.75rem;
}

.reward-toolbar__left {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex: 1;
  min-width: 0;
}

.reward-toolbar__right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.reward-toolbar__total {
  font-size: 0.8125rem;
  color: #9ca3af;
  white-space: nowrap;
}

.reward-batch {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.75rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #f9fafb;
}

.reward-batch__actions {
  flex-wrap: wrap;
}

.reward-pagination {
  margin-top: 0.75rem;
}

.reward-entity {
  cursor: pointer;
}

.reward-alert {
  margin-bottom: 1rem;
}

.reward-alert__link {
  border: none;
  background: transparent;
  color: #4ccba0;
  font-size: 0.8125rem;
  cursor: pointer;
  padding: 0;
}

.reward-alert__link:hover {
  text-decoration: underline;
}

/* 金额是列表里需要一眼看到的信息，视觉权重高于右侧的支付方式与时间。 */
.reward-amount {
  font-size: 0.9375rem;
  font-weight: 600;
  color: #1f2328;
  font-variant-numeric: tabular-nums;
}

.reward-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
}

.reward-item__body {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 0;
}

.reward-item__name {
  font-size: 0.875rem;
  font-weight: 600;
  color: #1f2328;
}

.reward-item__desc {
  font-size: 0.8125rem;
  color: #6b7280;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 26rem;
}

.reward-avatar {
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 8px;
  background: #fff;
  object-fit: cover;
  box-shadow:
    0 0 0 1px rgba(15, 23, 42, 0.08),
    0 1px 2px rgba(15, 23, 42, 0.06);
}
</style>
