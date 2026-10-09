import type { RecoveryRunDetail } from '../api/runRecovery'

/** 管理员切换选择时，以较新的详情读取结果为准。 */
export function createLatestRequestGeneration() {
  let latest = 0
  return {
    next(): number {
      latest += 1
      return latest
    },
    isCurrent(generation: number): boolean {
      return generation === latest
    },
  }
}

/** 界面确认必须由管理员明确操作，租约时间戳不能替代确认。 */
export function canSubmitTermination(
  detail: RecoveryRunDetail,
  stopEvidenceConfirmed: boolean,
  stopEvidenceReference: string,
  reason: string
): boolean {
  return detail.state === 'RECOVERY_REQUIRED'
    && detail.terminationEligibility.allowed
    && typeof detail.terminationEligibility.expectedFenceToken === 'number'
    && detail.terminationEligibility.expectedFenceToken > 0
    && stopEvidenceConfirmed
    && !!stopEvidenceReference.trim()
    && !!reason.trim()
}

export function recoveryActionRecorded(detail: RecoveryRunDetail, requestId: string): boolean {
  return detail.recoveryActions.some(action => action.requestId === requestId)
}

export function terminationEligibilityText(detail: RecoveryRunDetail): string {
  if (detail.state !== 'RECOVERY_REQUIRED') return '当前运行已离开待核查状态。'
  switch (detail.terminationEligibility.reasonCode) {
    case 'ELIGIBLE':
      return '数据库状态、profile、attempt 与 fence 满足终止接口前置条件；这不证明旧执行或外部动作已停止。'
    case 'LEGACY_PROFILE':
      return '旧版稳定运行不适用此处置流程。'
    case 'ATTEMPT_MISSING':
      return '缺少可核查的执行 attempt，不能终止。'
    case 'FENCE_UNAVAILABLE':
      return '缺少有效 fence，不能终止。'
    default:
      return '当前运行状态不允许终止。'
  }
}
