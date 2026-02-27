package net.weavemc.loader.bootstrap

import net.weavemc.loader.WeaveLoader
import net.weavemc.loader.api.util.asm
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LabelNode
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/**
 * The JavaAgent's `premain()` method, this is where initialization of Weave Loader begins.
 * Weave Loader's initialization begins by calling [WeaveLoader.init()][WeaveLoader.init], which is loaded through Genesis.
 */
@Suppress("UNUSED_PARAMETER")
public fun premain(opt: String?, inst: Instrumentation) {
    val version = findVersion()

    // FIX 1: Lunar Client sometimes omits --version entirely, or wraps the launch in a way
    // that the regex captures nothing (version == null). Also accept any 1.8.x variant
    // (e.g. "1.8.9-lunarclient", "1.8.9-optifine") instead of only exact strings.
    // Only hard-block if we positively detect a non-1.8 version string.
    val is18 = version == null || version == "1.8" || version.startsWith("1.8.")
    if (!is18) {
        println("[Weave] $version not supported, disabling...")
        return
    }

    if (version == null) {
        println("[Weave] Could not detect MC version from sun.java.command — assuming 1.8.9 (Lunar Client mode)")
    } else {
        println("[Weave] Detected version: $version")
    }

    // FIX 2: Wrap retransformClasses in a try/catch so that if sun.management.RuntimeImpl
    // is unavailable or already loaded differently (common on some Lunar Client builds),
    // the whole agent does not silently abort — it just skips the JVM-args-hiding patch.
    inst.addTransformer(object : ClassFileTransformer {
        override fun transform(
            loader: ClassLoader?,
            className: String,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray
        ): ByteArray? {
            if (className != "sun/management/RuntimeImpl" || classBeingRedefined == null) return null
            inst.removeTransformer(this)

            val node = ClassNode().also { ClassReader(classfileBuffer).accept(it, 0) }
            val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)

            with(node.methods.first { it.name == "getInputArguments" }) {
                val insn = instructions.first { it.opcode == Opcodes.ARETURN }
                instructions.insertBefore(insn, asm {
                    invokeinterface("java/lang/Iterable", "iterator", "()Ljava/util/Iterator;")
                    astore(2)
                    new("java/util/ArrayList")
                    dup
                    invokespecial("java/util/ArrayList", "<init>", "()V")
                    astore(3)

                    val loop = LabelNode()
                    val end = LabelNode()

                    +loop
                    aload(2)
                    invokeinterface("java/util/Iterator", "hasNext", "()Z")
                    ifeq(end)

                    aload(2)
                    invokeinterface("java/util/Iterator", "next", "()Ljava/lang/Object;")
                    checkcast("java/lang/String")
                    dup
                    astore(4)
                    ldc("javaagent")
                    invokevirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z")
                    ifne(loop)

                    aload(3)
                    aload(4)
                    invokeinterface("java/util/List", "add", "(Ljava/lang/Object;)Z")
                    pop

                    goto(loop)
                    +end

                    aload(3)
                })
            }

            node.accept(writer)
            return writer.toByteArray()
        }
    }, true)

    try {
        inst.retransformClasses(Class.forName("sun.management.RuntimeImpl", false, ClassLoader.getSystemClassLoader()))
    } catch (e: Exception) {
        // FIX 2: Don't crash if RuntimeImpl isn't retransformable on this JVM/Lunar build.
        // The javaagent-args-hiding patch is cosmetic; Weave can still load mods without it.
        println("[Weave] Warning: could not patch RuntimeImpl.getInputArguments (${e.javaClass.simpleName}: ${e.message})")
    }

    inst.addTransformer(URLClassLoaderTransformer)

    // FIX 3+4: Initialize Weave exactly when net/minecraft/client/Minecraft is being loaded.
    //
    // BUG RAIZ del menú de cosméticos: el trigger original usaba startsWith("net/minecraft/client/"),
    // que podía dispararse con cualquier clase del paquete (ej. GuiScreen, FontRenderer, etc.)
    // ANTES de que Minecraft.class fuera cargada. Weave se inicializaba, registraba los hooks,
    // pero Minecraft.class ya estaba cargada — así que GuiOpenEventHook y StartGameEventHook
    // (ambos targeting "net/minecraft/client/Minecraft") NUNCA se aplicaban al bytecode.
    // Resultado: displayGuiScreen no estaba instrumentado → el menú de cosméticos no abría
    // desde el menú principal (donde Lunar lo invoca vía GuiOpenEvent).
    //
    // Al esperar exactamente a "net/minecraft/client/Minecraft", garantizamos que los hooks
    // se registran antes de que esa clase termine de cargarse, y el HookManager la transforma.
    inst.addTransformer(object : SafeTransformer {
        override fun transform(loader: ClassLoader, className: String, originalClass: ByteArray): ByteArray? {
            if (className != "net/minecraft/client/Minecraft") return null

            inst.removeTransformer(URLClassLoaderTransformer)
            inst.removeTransformer(this)

            try {
                (loader as URLClassLoaderAccessor).addWeaveURL(javaClass.protectionDomain.codeSource.location)
                loader.loadClass("net.weavemc.loader.WeaveLoader")
                    .getDeclaredMethod("init", Instrumentation::class.java)
                    .invoke(null, inst)
                println("[Weave] Initialized")
            } catch (e: Exception) {
                System.err.println("[Weave] ERROR: Failed to initialize WeaveLoader!")
                e.printStackTrace()
                // Do NOT rethrow — let Minecraft continue to boot without Weave.
            }

            // Retornar null aquí: el HookManager (registrado dentro de init()) ya
            // está activo y transformará esta misma clase correctamente.
            return null
        }
    })
}

/**
 * Reads the Minecraft version from the JVM's `sun.java.command` system property.
 *
 * Lunar Client passes `--version <ver>` in the command string.
 * Returns null if the flag is absent (handled gracefully in [premain]).
 */
private fun findVersion(): String? =
    System.getProperty("sun.java.command")
        ?.let { """--version\s+(\S+)""".toRegex().find(it)?.groupValues?.get(1) }
