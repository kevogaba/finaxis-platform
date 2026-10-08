package com.finaxis.platform.iam.config

import com.finaxis.platform.iam.FixedViewRequirements
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.method.HandlerMethod

/**
 * The published description of a mutation route lists both codes it needs (ADR 0030), read from
 * the same pairing the guard enforces. Runs on hand-built models, without a Spring context.
 */
class PermissionRequirementOpenApiConfigurationTests {
    private val customizer =
        PermissionRequirementOpenApiConfiguration().permissionRequirementOperationCustomizer(
            FixedViewRequirements(
                mapOf(
                    "branch.suspend" to listOf("branch.view"),
                    "branch.create" to listOf("branch.view"),
                    "branch.approve" to listOf("branch.view"),
                    "user.invite" to listOf("membership.view", "user.view"),
                ),
            ),
        )

    @Test
    fun `a mutation route lists its code and its view`() {
        val operation = customise("suspend", Operation().description("Suspends a branch."))

        assertThat(operation.description)
            .startsWith("Suspends a branch.")
            .contains("Requires `branch.suspend` and its view permission `branch.view`")
            .contains("403")
    }

    @Test
    fun `a route needing two views lists both`() {
        val operation = customise("invite", Operation())

        assertThat(operation.description)
            .contains("`membership.view` and `user.view`")
            .contains("view permissions")
    }

    @Test
    fun `an any-of gate lists each mutation code`() {
        val operation = customise("returnBranch", Operation())

        assertThat(operation.description)
            .contains("Requires any one of `branch.create` or `branch.approve`")
            .contains("`branch.approve` needs `branch.view`")
            .doesNotContain("Requires `branch.approve`")
    }

    @Test
    fun `the 403 response keeps its own text and lists the code and its views`() {
        val operation =
            customise(
                "invite",
                Operation().responses(
                    ApiResponses().addApiResponse("403", ApiResponse().description("Forbidden")),
                ),
            )

        assertThat(operation.responses["403"]?.description)
            .startsWith("Forbidden. Requires `user.invite`")
            .contains("`membership.view` and `user.view`")
            .contains("naming the first missing code")
    }

    @Test
    fun `a mutation route with no 403 response gains one`() {
        val operation = customise("suspend", Operation())

        assertThat(operation.responses["403"]?.description)
            .startsWith("Requires `branch.suspend` and its view permission `branch.view`")
        assertThat(operation.responses["403"]?.content).containsKey("application/problem+json")
    }

    @Test
    fun `an any-of gate lists every code in the 403 response`() {
        val operation = customise("returnBranch", Operation())

        assertThat(operation.responses["403"]?.description)
            .contains("Requires any one of `branch.create` or `branch.approve`")
            .contains("`branch.create` needs `branch.view`")
    }

    @Test
    fun `a gate on the controller class is found when the method has none`() {
        val operation =
            customizer.customize(
                Operation(),
                HandlerMethod(ClassGated(), ClassGated::class.java.getMethod("act")),
            )

        assertThat(operation.responses["403"]?.description)
            .contains("Requires `branch.suspend` and its view permission `branch.view`")
        assertThat(operation.description).contains("`branch.suspend`")
    }

    @Test
    fun `a view route or an unannotated route is left alone`() {
        assertThat(customise("read", Operation().description("Reads.")).description)
            .isEqualTo("Reads.")
        assertThat(customise("open", Operation().description("Open.")).description)
            .isEqualTo("Open.")
        assertThat(customise("read", Operation()).responses).isNull()
        assertThat(customise("open", Operation()).responses).isNull()
    }

    private fun customise(
        method: String,
        operation: Operation,
    ): Operation =
        customizer.customize(
            operation,
            HandlerMethod(Routes(), Routes::class.java.getMethod(method)),
        )

    @PreAuthorize("hasAuthority('branch.suspend')")
    class ClassGated {
        fun act(): String = toString()
    }

    @Suppress("FunctionOnlyReturningConstant", "EmptyFunctionBlock")
    class Routes {
        @PreAuthorize("hasAuthority('branch.suspend')")
        fun suspend() {
        }

        @PreAuthorize("hasAuthority('user.invite')")
        fun invite() {
        }

        @PreAuthorize("hasAnyAuthority('branch.create', 'branch.approve')")
        fun returnBranch() {
        }

        @PreAuthorize("hasAuthority('branch.view')")
        fun read() {
        }

        fun open() {
        }
    }
}
