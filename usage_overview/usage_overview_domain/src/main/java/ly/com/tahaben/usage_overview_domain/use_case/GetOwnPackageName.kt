package ly.com.tahaben.usage_overview_domain.use_case

import ly.com.tahaben.usage_overview_domain.repository.UsageRepository

class GetOwnPackageName(
    private val usageRepository: UsageRepository
) {
    operator fun invoke(): String = usageRepository.getOwnPackageName()
}
