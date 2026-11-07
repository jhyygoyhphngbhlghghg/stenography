import { Canvas, useFrame } from "@react-three/fiber";
import { useMemo, useRef } from "react";
import * as THREE from "three";
import { scrollState } from "./state";

// three.js cannot read oklch tokens; this mirrors --neon.
const NEON = "#9dff3a";
const STEP = 7; // world units between checkpoints

function Shape({ i, children }: { i: number; children: React.ReactNode }) {
  const ref = useRef<THREE.Mesh>(null);
  const side = i % 2 === 0 ? 1 : -1;
  useFrame((_, dt) => {
    const m = ref.current;
    if (!m) return;
    const d = Math.min(dt, 0.05);
    m.rotation.x += d * 0.25;
    m.rotation.y += d * 0.35;
    const near = 1 - Math.min(1, Math.abs(scrollState.p - i));
    const s = THREE.MathUtils.damp(m.scale.x, 0.7 + near * 0.5, 3, d);
    m.scale.setScalar(s);
    (m.material as THREE.MeshBasicMaterial).opacity = 0.05 + near * 0.17;
  });
  return (
    <mesh ref={ref} position={[side * 6, -i * STEP, -5]}>
      {children}
      <meshBasicMaterial color={NEON} wireframe transparent opacity={0.3} />
    </mesh>
  );
}

function Particles() {
  const geo = useMemo(() => {
    const n = 1400;
    const arr = new Float32Array(n * 3);
    for (let k = 0; k < n; k++) {
      arr[k * 3] = (Math.random() - 0.5) * 30;
      arr[k * 3 + 1] = 6 - Math.random() * STEP * 9;
      arr[k * 3 + 2] = -Math.random() * 20;
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.BufferAttribute(arr, 3));
    return g;
  }, []);
  return (
    <points geometry={geo}>
      <pointsMaterial color={NEON} size={0.03} transparent opacity={0.4} />
    </points>
  );
}

function Rig() {
  useFrame((state, dt) => {
    const d = Math.min(dt, 0.05);
    const c = state.camera;
    const p = scrollState.p;
    c.position.y = THREE.MathUtils.damp(c.position.y, -p * STEP, 6, d);
    c.position.x = THREE.MathUtils.damp(
      c.position.x,
      Math.sin(p * 1.4) * 1.2 + state.pointer.x * 0.4,
      3,
      d,
    );
    c.position.z = 9;
    c.rotation.z = THREE.MathUtils.damp(c.rotation.z, Math.sin(p * 0.9) * 0.06, 3, d);
  });
  return null;
}

export default function Scene3D({ quiet = false }: { quiet?: boolean }) {
  return (
    <div
      className={`pointer-events-none fixed inset-0 z-0 transition-opacity duration-1000 ${quiet ? "opacity-15" : "opacity-100"}`}
    >
      <Canvas
        camera={{ position: [0, 0, 9], fov: 50 }}
        dpr={[1, 1.5]}
        gl={{ alpha: true, antialias: true }}
      >
        <Rig />
        <Particles />
        <Shape i={0}>
          <icosahedronGeometry args={[2, 1]} />
        </Shape>
        <Shape i={1}>
          <torusKnotGeometry args={[1.3, 0.38, 120, 12]} />
        </Shape>
        <Shape i={2}>
          <octahedronGeometry args={[2, 0]} />
        </Shape>
        <Shape i={3}>
          <torusGeometry args={[1.6, 0.5, 16, 40]} />
        </Shape>
        <Shape i={4}>
          <dodecahedronGeometry args={[1.9, 0]} />
        </Shape>
        <Shape i={5}>
          <sphereGeometry args={[1.9, 24, 16]} />
        </Shape>
        <Shape i={6}>
          <boxGeometry args={[2.4, 2.4, 2.4, 3, 3, 3]} />
        </Shape>
        <Shape i={7}>
          <icosahedronGeometry args={[2, 0]} />
        </Shape>
      </Canvas>
    </div>
  );
}
