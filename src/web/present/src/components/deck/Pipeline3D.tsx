import { Canvas, useFrame } from "@react-three/fiber";
import { useEffect, useMemo, useRef, useState } from "react";
import * as THREE from "three";
import { voiceSignal } from "./dialogue";

type Palette = { signal: string; secondary: string; surface: string };
function Network({ active, palette }: { active: boolean; palette: Palette }) {
  const group = useRef<THREE.Group>(null);
  const bars = useRef<Array<THREE.Mesh | null>>([]);
  const blocks = useRef<Array<THREE.Group | null>>([]);
  const packets = useRef<Array<THREE.Mesh | null>>([]);
  const started = useRef<number | null>(null);
  const paths = useMemo(
    () =>
      [-1.25, 1.25].map(
        (y) =>
          new THREE.CatmullRomCurve3([
            new THREE.Vector3(-2.05, 0, 0),
            new THREE.Vector3(-1.1, y, 0),
            new THREE.Vector3(0.6, y, 0),
            new THREE.Vector3(2.15, y, 0),
            new THREE.Vector3(3.35, 0, 0),
          ]),
      ),
    [],
  );
  useEffect(() => {
    started.current = null;
  }, [active]);
  useFrame(({ clock, gl, camera, size }, dt) => {
    if (camera instanceof THREE.OrthographicCamera) {
      camera.zoom = Math.min(size.width / 11, size.height / 5.3);
      camera.updateProjectionMatrix();
    }
    const root = group.current;
    if (!root) return;
    const now = clock.elapsedTime;
    if (active && started.current === null) started.current = now;
    const age = started.current === null ? 0 : now - started.current;
    const reveal = active ? Math.min(1, age / 2) : 0;
    root.scale.setScalar(
      THREE.MathUtils.damp(root.scale.x, active ? 1 : 0.86, 2.5, Math.min(dt, 0.05)),
    );
    bars.current.forEach((bar, i) => {
      if (!bar) return;
      const envelope = Math.pow(Math.sin((i / 47) * Math.PI), 1.4);
      const motion = voiceSignal.connected
        ? voiceSignal.level
        : 0.34 + 0.2 * Math.sin(now * 1.8 + i * 0.3);
      const target = 0.08 + envelope * (0.2 + motion) * (0.7 + 0.3 * Math.sin(i * 1.9 + now * 2));
      bar.scale.y = THREE.MathUtils.damp(bar.scale.y, target * reveal, 5, Math.min(dt, 0.05));
    });
    blocks.current.forEach((block, i) => {
      if (!block) return;
      block.rotation.y = -0.35 + Math.sin(now * 0.5 + i) * 0.08;
      block.rotation.x = 0.18;
      block.scale.setScalar(
        THREE.MathUtils.damp(block.scale.x, active && age > 1.5 ? 1 : 0.01, 3, Math.min(dt, 0.05)),
      );
    });
    packets.current.forEach((packet, i) => {
      if (!packet) return;
      const path = paths[i % 2];
      if (!path) return;
      packet.visible = active && age > 2;
      packet.position.copy(path.getPoint((now * 0.22 + i / 6) % 1));
    });
    gl.domElement.parentElement?.setAttribute("data-scene-ready", "true");
  });
  return (
    <group ref={group}>
      {Array.from({ length: 48 }, (_, i) => (
        <mesh
          key={i}
          ref={(node) => {
            bars.current[i] = node;
          }}
          position={[-4.8 + i * 0.055, 0, 0]}
        >
          <boxGeometry args={[0.025, 1.65, 0.12]} />
          <meshBasicMaterial color={palette.signal} transparent opacity={0.85} />
        </mesh>
      ))}
      {paths.map((path, i) => (
        <mesh key={i}>
          <tubeGeometry args={[path, 40, 0.012, 5, false]} />
          <meshBasicMaterial
            color={i ? palette.secondary : palette.signal}
            transparent
            opacity={0.4}
          />
        </mesh>
      ))}
      {[-1.25, 1.25].map((y, i) => (
        <group
          key={i}
          ref={(node) => {
            blocks.current[i] = node;
          }}
          position={[0.6, y, 0]}
        >
          <mesh>
            <boxGeometry args={[1.35, 0.72, 0.65]} />
            <meshStandardMaterial
              color={palette.surface}
              metalness={0.45}
              roughness={0.35}
              transparent
              opacity={0.85}
            />
          </mesh>
          <mesh>
            <boxGeometry args={[1.36, 0.73, 0.66]} />
            <meshBasicMaterial
              color={i ? palette.secondary : palette.signal}
              wireframe
              transparent
              opacity={0.7}
            />
          </mesh>
          {Array.from({ length: 5 }, (_, j) => (
            <mesh key={j} position={[-0.45 + j * 0.22, 0, 0.34]}>
              <boxGeometry args={[0.08, 0.26, 0.02]} />
              <meshBasicMaterial color={palette.signal} transparent opacity={0.4} />
            </mesh>
          ))}
        </group>
      ))}
      {Array.from({ length: 6 }, (_, i) => (
        <mesh
          key={i}
          ref={(node) => {
            packets.current[i] = node;
          }}
        >
          <sphereGeometry args={[0.045, 8, 8]} />
          <meshBasicMaterial color={i % 2 ? palette.secondary : palette.signal} />
        </mesh>
      ))}
      <mesh position={[3.8, 0, 0]}>
        <planeGeometry args={[1.5, 0.95]} />
        <meshBasicMaterial color={palette.surface} transparent opacity={0.7} />
      </mesh>
      {Array.from({ length: 5 }, (_, i) => (
        <mesh key={i} position={[3.7 - (i % 2) * 0.15, 0.3 - i * 0.15, 0.03]}>
          <boxGeometry args={[0.8 + (i % 2) * 0.3, 0.025, 0.02]} />
          <meshBasicMaterial color={palette.signal} transparent opacity={0.6} />
        </mesh>
      ))}
    </group>
  );
}
export default function Pipeline3D({ active }: { active: boolean }) {
  const [palette, setPalette] = useState<Palette | null>(null);
  useEffect(() => {
    const tokens = getComputedStyle(document.documentElement);
    setPalette({
      signal: tokens.getPropertyValue("--scene-signal").trim(),
      secondary: tokens.getPropertyValue("--scene-secondary").trim(),
      surface: tokens.getPropertyValue("--scene-surface").trim(),
    });
  }, []);
  if (!palette) return null;
  return (
    <Canvas
      orthographic
      camera={{ position: [0, 0, 12], zoom: 48 }}
      dpr={[1, 1.5]}
      gl={{ alpha: true, antialias: true }}
    >
      <ambientLight intensity={1.3} />
      <directionalLight position={[2, 5, 6]} intensity={2} />
      <Network active={active} palette={palette} />
    </Canvas>
  );
}
