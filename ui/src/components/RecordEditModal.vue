<script lang="ts" setup>
import { axiosInstance } from "@halo-dev/api-client";
import { Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { computed, ref } from "vue";

const API_BASE = "/apis/console.api.reward.nanzhiy.cn/v1alpha1";

interface RewardRecordSpec {
  amount?: number;
  nickname?: string;
  message?: string;
  contact?: string;
  transactionNo?: string;
  avatar?: string;
  method?: string;
  status?: string;
}

interface RewardRecord {
  metadata: { name: string };
  spec: RewardRecordSpec;
}

const props = defineProps<{ record?: RewardRecord }>();

const emit = defineEmits<{
  (event: "close"): void;
  (event: "saved"): void;
}>();

const modal = ref<InstanceType<typeof VModal> | null>(null);
const saving = ref(false);
const isEdit = computed(() => Boolean(props.record));

const formValue = ref({
  amount: props.record?.spec.amount ?? 0,
  nickname: props.record?.spec.nickname ?? "",
  message: props.record?.spec.message ?? "",
  contact: props.record?.spec.contact ?? "",
  transactionNo: props.record?.spec.transactionNo ?? "",
  avatar: props.record?.spec.avatar ?? "",
  method: props.record?.spec.method ?? "wechat",
  status: props.record?.spec.status ?? "PENDING",
});

const statusOptions = [
  { label: "待确认", value: "PENDING" },
  { label: "已确认", value: "CONFIRMED" },
  { label: "已拒绝", value: "REJECTED" },
];

const methodOptions = [
  { label: "微信", value: "wechat" },
  { label: "支付宝", value: "alipay" },
];

async function onSubmit(values: Record<string, unknown>) {
  saving.value = true;
  try {
    if (isEdit.value && props.record) {
      await axiosInstance.put(`${API_BASE}/records/${props.record.metadata.name}`, values);
    } else {
      await axiosInstance.post(`${API_BASE}/records`, values);
    }
    Toast.success(isEdit.value ? "保存成功" : "新增成功");
    emit("saved");
    modal.value?.close();
    emit("close");
  } catch (error: any) {
    Toast.error(error?.response?.data?.detail || "保存失败");
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <VModal
    ref="modal"
    :title="isEdit ? '编辑打赏记录' : '新增打赏记录'"
    :width="680"
    :mount-to-body="true"
    @close="emit('close')"
  >
    <FormKit
      id="reward-record-form"
      type="form"
      :actions="false"
      :value="formValue"
      :config="{ validationVisibility: 'submit' }"
      @submit="onSubmit"
    >
      <div class="record-form">
        <FormKit type="number" name="amount" label="打赏金额" step="0.01" min="0.01" max="1000000" validation="required|min:0.01|max:1000000" />
        <FormKit type="text" name="nickname" label="昵称" maxlength="30" validation="required" />
        <FormKit type="text" name="transactionNo" label="交易单号" maxlength="64" help="用于与收款记录核对" />
        <FormKit
          type="attachment"
          name="avatar"
          label="头像"
          help="从附件库选择图片，展示在赞助者墙与后台列表"
          accepts="image/*"
          :width="'96px'"
          :aspect-ratio="'1/1'"
        />
        <FormKit type="textarea" name="message" label="留言" maxlength="200" :rows="3" />
        <FormKit type="text" name="contact" label="联系方式" maxlength="100" help="仅后台可见，不会展示在前台" />
        <FormKit type="select" name="method" label="支付方式" :options="methodOptions" />
        <FormKit type="select" name="status" label="状态" :options="statusOptions" />
      </div>
    </FormKit>

    <template #footer>
      <VSpace>
        <!-- @vue-ignore -->
        <VButton type="secondary" :loading="saving" @click="$formkit.submit('reward-record-form')">
          保存
        </VButton>
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
