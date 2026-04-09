//javac -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainGeneration.java
//java -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainGeneration

import java.awt.image.BufferedImage; //ST
import java.io.BufferedReader; //ST
import java.io.File;
import java.io.FileReader; //ST
import java.io.IOException; //ST
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO; //ST

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

public class TerrainGeneration { //ST
    private long window;
    private int width = 800;
    private int height = 600;
    private Terrain terrain; //ST
    private BulletSystem bulletSystem; //ST: bullet rain

    public static void main(String[] args) {
        new TerrainGeneration().run();
    }

    public void run() {
        init();
        loop();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private void init() { //ST
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        window = GLFW.glfwCreateWindow(width, height, "Terrain Generation - Bullet Rain", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        // ST: sky-blue background so bullets stand out against the scene
        GL11.glClearColor(0.53f, 0.81f, 0.98f, 1.0f);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        setPerspectiveProjection(60.0f, (float) width / height, 0.1f, 2000.0f); // ST: wider FOV + far plane
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        GL11.glEnable(GL11.GL_TEXTURE_2D); //ST

        // ST: enable lighting so bullets have depth and shading
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_LIGHT0);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);

        // ST: bright overhead sun light
        FloatBuffer lightPos = BufferUtils.createFloatBuffer(4);
        lightPos.put(new float[]{0.5f, 1.0f, 0.5f, 0.0f}).flip(); // directional
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPos);

        FloatBuffer lightDiff = BufferUtils.createFloatBuffer(4);
        lightDiff.put(new float[]{1.0f, 0.95f, 0.85f, 1.0f}).flip(); // warm sun colour
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, lightDiff);

        FloatBuffer lightAmb = BufferUtils.createFloatBuffer(4);
        lightAmb.put(new float[]{0.35f, 0.35f, 0.4f, 1.0f}).flip(); // cool ambient fill
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_AMBIENT, lightAmb);

        // ST: smooth shading and normalise after glScale
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_NORMALIZE);

        terrain = new Terrain("fractal_terrain.obj", "terrain.png"); //ST: load OBJ + texture

        // ST: init bullet system using terrain bounds so bullets spawn above the terrain
        bulletSystem = new BulletSystem(
                terrain.getMinX(), terrain.getMaxX(),
                terrain.getMinZ(), terrain.getMaxZ(),
                terrain // NEW: pass terrain so bullets can query height
        );
    }

    private void loop() { //ST
        long lastTime = System.nanoTime();

        while (!GLFW.glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            float dt = (now - lastTime) / 1_000_000_000.0f; // seconds
            lastTime = now;

            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glLoadIdentity();

            // ST: flat horizontal view — camera sits at terrain level looking straight
            // at the terrain face-on; bullets fall from above into the scene
            float centerX = terrain.getCenterX();
            float maxDim  = terrain.getMaxDimension();

            // no X rotation = perfectly level camera
            // pull back far enough to see the full terrain width, sit at mid-height
            GL11.glTranslatef(-centerX, -maxDim * 0.15f, -maxDim * 1.4f);

            terrain.render(); //ST

            bulletSystem.update(dt); //ST: update bullet positions
            bulletSystem.render();   //ST: draw all bullets

            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    private void setPerspectiveProjection(float fov, float aspect, float zNear, float zFar) { //book
        float ymax = (float)(zNear * Math.tan(Math.toRadians(fov / 2.0)));
        float xmax = ymax * aspect;

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glFrustum(-xmax, xmax, -ymax, ymax, zNear, zFar);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
}

// ST: manages spawning and updating a rain of small and large bullets
class BulletSystem {
    private static final int   MAX_BULLETS  = 80;    // plenty of bullets visible at once
    private static final float SPAWN_RATE   = 12.0f; // bullets per second
    private static final float FALL_SPEED   = 18.0f; // units per second downward
    private static final float SPAWN_HEIGHT = 50.0f; // high enough to see them falling in
    private static final float KILL_Y       = -5.0f; // cull once below terrain surface

    // ST: scale large enough to be clearly visible in the scene
    private static final float SMALL_SCALE = 1.8f;
    private static final float LARGE_SCALE = 3.2f;

    // NEW: physics constants for bounce behaviour
    private static final float GRAVITY         = 25.0f;  // NEW: downward acceleration (units/s^2)
    private static final float BOUNCE_DAMPEN   = 0.45f;  // NEW: fraction of vertical speed kept after bounce (0=dead, 1=perfect)
    private static final float LATERAL_DAMPEN  = 0.75f;  // NEW: fraction of horizontal speed kept after bounce
    private static final float MIN_BOUNCE_SPD  = 1.5f;   // NEW: if bounce speed falls below this, bullet comes to rest
    private static final float MAX_BOUNCES     = 5;      // NEW: maximum number of bounces before bullet dies

    private final List<Bullet> bullets = new ArrayList<>();
    private final Random rng = new Random();
    private float spawnAccumulator = 0.0f;

    private final float minX, maxX, minZ, maxZ;

    private final OBJMesh smallMesh;
    private final OBJMesh largeMesh;
    private final int smallTexture;
    private final int largeTexture;

    private final Terrain terrain; // NEW: reference to terrain for height sampling

    public BulletSystem(float minX, float maxX, float minZ, float maxZ, Terrain terrain) { // NEW: terrain param added
        this.minX = minX; this.maxX = maxX;
        this.minZ = minZ; this.maxZ = maxZ;
        this.terrain = terrain; // NEW

        smallMesh    = new OBJMesh("small_bullet.obj");
        largeMesh    = new OBJMesh("large_bullet.obj");
        smallTexture = TextureLoader.load("small_bullet.png");
        largeTexture = TextureLoader.load("large_bullet.png");
    }

    public void update(float dt) {
        spawnAccumulator += dt * SPAWN_RATE;
        while (spawnAccumulator >= 1.0f && bullets.size() < MAX_BULLETS) {
            spawnAccumulator -= 1.0f;
            spawnBullet();
        }

        // NEW: replaced simple fall with physics update including bounce detection
        bullets.removeIf(b -> {
            if (b.resting) return false; // NEW: skip physics for resting bullets, keep them alive to show

            // NEW: apply gravity to vertical velocity
            b.vy -= GRAVITY * dt;

            // NEW: update position using velocity
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.z += b.vz * dt;

            // NEW: clamp x/z inside terrain bounds so we can always sample height
            float cx = Math.max(minX, Math.min(maxX, b.x));
            float cz = Math.max(minZ, Math.min(maxZ, b.z));

            // NEW: sample terrain height and surface normal at bullet's position
            float terrainY  = terrain.getHeightAt(cx, cz);
            float[] normal  = terrain.getNormalAt(cx, cz); // NEW: slope-aware normal

            // NEW: bounce when bullet reaches or passes through the terrain surface
            if (b.y <= terrainY) {
                b.y = terrainY; // NEW: push bullet back to surface
                b.bounceCount++;

                // NEW: reflect velocity off the terrain surface normal
                // reflection formula: v' = v - 2*(v dot n)*n
                float dot = b.vx * normal[0] + b.vy * normal[1] + b.vz * normal[2];
                float rx  = b.vx - 2 * dot * normal[0];
                float ry  = b.vy - 2 * dot * normal[1];
                float rz  = b.vz - 2 * dot * normal[2];

                // NEW: apply damping — less energy after each bounce
                b.vx = rx * LATERAL_DAMPEN;
                b.vy = ry * BOUNCE_DAMPEN;
                b.vz = rz * LATERAL_DAMPEN;

                // NEW: if bounce is too weak or too many bounces, come to rest
                float speed = (float) Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
                if (speed < MIN_BOUNCE_SPD || b.bounceCount >= MAX_BOUNCES) {
                    b.resting = true;
                    b.vx = 0; b.vy = 0; b.vz = 0;
                }
            }

            // NEW: remove bullet if it drifts far outside the scene or falls through (failsafe)
            return b.y < KILL_Y && !b.resting;
        });
    }

    private void spawnBullet() {
        float x = minX + rng.nextFloat() * (maxX - minX);
        float z = minZ + rng.nextFloat() * (maxZ - minZ);
        boolean large = rng.nextBoolean();

        // NEW: give each bullet a small random horizontal drift so bounces spread naturally
        float vx = (rng.nextFloat() - 0.5f) * 4.0f; // NEW
        float vz = (rng.nextFloat() - 0.5f) * 4.0f; // NEW

        bullets.add(new Bullet(x, SPAWN_HEIGHT, z, large, vx, -FALL_SPEED, vz)); // NEW: initial velocity
    }

    public void render() {
        for (Bullet b : bullets) {
            GL11.glPushMatrix();

            GL11.glTranslatef(b.x, b.y, b.z);

            // NEW: tilt bullet to match velocity direction while it's moving; lay flat when resting
            if (!b.resting) {
                float speed = (float) Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
                if (speed > 0.01f) {
                    // NEW: point the bullet nose along its velocity vector
                    float pitch = (float) Math.toDegrees(Math.asin(-b.vy / speed));
                    float yaw   = (float) Math.toDegrees(Math.atan2(b.vx, b.vz));
                    GL11.glRotatef(yaw,   0, 1, 0); // NEW: horizontal heading
                    GL11.glRotatef(pitch, 1, 0, 0); // NEW: nose-down angle
                }
            } else {
                // ST: rotate so bullet points nose-down (falling posture)
                GL11.glRotatef(180, 1, 0, 0);
            }

            if (b.large) {
                GL11.glScalef(LARGE_SCALE, LARGE_SCALE, LARGE_SCALE);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, largeTexture);
                largeMesh.render();
            } else {
                GL11.glScalef(SMALL_SCALE, SMALL_SCALE, SMALL_SCALE);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, smallTexture);
                smallMesh.render();
            }

            GL11.glPopMatrix();
        }
    }

    private static class Bullet {
        float x, y, z;
        float vx, vy, vz; // NEW: velocity components
        boolean large;
        boolean resting;  // NEW: true once bullet has come to rest on terrain
        int bounceCount;  // NEW: tracks how many times the bullet has bounced

        // NEW: updated constructor to accept initial velocity
        Bullet(float x, float y, float z, boolean large, float vx, float vy, float vz) {
            this.x = x; this.y = y; this.z = z;
            this.large = large;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.resting = false; // NEW
            this.bounceCount = 0; // NEW
        }
    }
}

// ST: loads and renders an OBJ mesh; parses vn normals for lighting
class OBJMesh {
    private final List<float[]> vertices  = new ArrayList<>();
    private final List<float[]> normals   = new ArrayList<>(); // ST: for lighting
    private final List<float[]> texCoords = new ArrayList<>();
    private final List<int[]>   faces     = new ArrayList<>(); // 9 ints: v,vt,vn x3

    public OBJMesh(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("vn ")) {
                    String[] t = line.split("\\s+");
                    normals.add(new float[]{
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2]),
                            Float.parseFloat(t[3])
                    });
                } else if (line.startsWith("v ")) {
                    String[] t = line.split("\\s+");
                    vertices.add(new float[]{
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2]),
                            Float.parseFloat(t[3])
                    });
                } else if (line.startsWith("vt ")) {
                    String[] t = line.split("\\s+");
                    texCoords.add(new float[]{
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2])
                    });
                } else if (line.startsWith("f ")) {
                    // ST: supports v, v/vt, v/vt/vn, v//vn
                    String[] t = line.split("\\s+");
                    int[] face = new int[9];
                    for (int i = 0; i < 3; i++) {
                        String[] parts = t[i + 1].split("/");
                        face[i*3]     = Integer.parseInt(parts[0]) - 1;
                        face[i*3 + 1] = (parts.length > 1 && !parts[1].isEmpty())
                                ? Integer.parseInt(parts[1]) - 1 : -1;
                        face[i*3 + 2] = (parts.length > 2 && !parts[2].isEmpty())
                                ? Integer.parseInt(parts[2]) - 1 : -1;
                    }
                    faces.add(face);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (texCoords.isEmpty()) texCoords.add(new float[]{0, 0});
        if (normals.isEmpty())   normals.add(new float[]{0, 1, 0});
    }

    public void render() {
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int[] f : faces) {
            for (int i = 0; i < 3; i++) {
                int vni = f[i*3 + 2];
                int vti = f[i*3 + 1];
                int vi  = f[i*3];

                if (vni >= 0) { float[] n = normals.get(vni);   GL11.glNormal3f(n[0], n[1], n[2]); }
                if (vti >= 0) { float[] uv = texCoords.get(vti); GL11.glTexCoord2f(uv[0], uv[1]); }
                float[] v = vertices.get(vi);
                GL11.glVertex3f(v[0], v[1], v[2]);
            }
        }
        GL11.glEnd();
    }

    // NEW: expose vertex and normal data so Terrain can build a height/normal lookup
    public List<float[]> getVertices() { return vertices; } // NEW
    public List<float[]> getNormals()  { return normals;  } // NEW
    public List<int[]>   getFaces()    { return faces;    } // NEW
}

// ST: static texture loader — RGBA so alpha channels work
class TextureLoader {
    public static int load(String path) {
        try {
            BufferedImage img = ImageIO.read(new File(path));
            int w = img.getWidth(), h = img.getHeight();
            int[] pixels = new int[w * h];
            img.getRGB(0, 0, w, h, pixels, 0, w);

            FloatBuffer buffer = BufferUtils.createFloatBuffer(w * h * 4);
            for (int pixel : pixels) {
                buffer.put(((pixel >> 16) & 0xFF) / 255.0f); // R
                buffer.put(((pixel >> 8)  & 0xFF) / 255.0f); // G
                buffer.put((pixel         & 0xFF) / 255.0f); // B
                buffer.put(((pixel >> 24) & 0xFF) / 255.0f); // A
            }
            buffer.flip();

            int texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL12.GL_BGRA, GL11.GL_FLOAT, buffer);
            return texId;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }
}

//ST: Terrain class for OBJ mesh + texture
class Terrain {
    private final OBJMesh mesh;
    private int textureId;

    private float minX = Float.MAX_VALUE,  maxX = -Float.MAX_VALUE;
    private float minZ = Float.MAX_VALUE,  maxZ = -Float.MAX_VALUE;

    // NEW: heightmap and normal map arrays for fast bullet collision sampling
    private int   gridW, gridH;           // NEW: grid dimensions (256x256 for fractal_terrain)
    private float[] heightGrid;           // NEW: [gridW * gridH] sampled heights
    private float[][] normalGrid;         // NEW: [gridW * gridH][3] sampled normals

    public Terrain(String objPath, String texturePath) {
        mesh = new OBJMesh(objPath);
        parseBounds(objPath);
        textureId = TextureLoader.load(texturePath);
        buildHeightGrid(); // NEW: build lookup table after mesh is loaded
    }

    // NEW: walks vertices to populate a fast height + normal lookup grid
    private void buildHeightGrid() {
        // NEW: the fractal_terrain OBJ lays out vertices as integer x,z coords (0..255)
        // We can compute grid dimensions from min/max x and z
        gridW = Math.round(maxX - minX) + 1; // NEW
        gridH = Math.round(maxZ - minZ) + 1; // NEW

        heightGrid = new float[gridW * gridH]; // NEW
        normalGrid = new float[gridW * gridH][]; // NEW

        // NEW: first pass — record height at each integer grid cell from vertex list
        java.util.List<float[]> verts   = mesh.getVertices(); // NEW
        java.util.List<float[]> normals = mesh.getNormals();  // NEW
        java.util.List<int[]>   faces   = mesh.getFaces();    // NEW

        // NEW: accumulate normals per grid cell (average across shared faces)
        float[][] normAccum = new float[gridW * gridH][3]; // NEW
        int[]     normCount = new int[gridW * gridH];      // NEW

        // NEW: build height grid from vertex positions
        for (float[] v : verts) { // NEW
            int xi = Math.round(v[0] - minX); // NEW
            int zi = Math.round(v[2] - minZ); // NEW
            if (xi >= 0 && xi < gridW && zi >= 0 && zi < gridH) { // NEW
                heightGrid[zi * gridW + xi] = v[1]; // NEW: y is the height
            } // NEW
        } // NEW

        // NEW: accumulate vertex normals per grid cell
        for (int[] f : faces) { // NEW
            for (int i = 0; i < 3; i++) { // NEW
                int vi  = f[i*3];     // NEW
                int vni = f[i*3 + 2]; // NEW
                if (vi < 0 || vi >= verts.size()) continue; // NEW
                float[] v = verts.get(vi); // NEW
                int xi = Math.round(v[0] - minX); // NEW
                int zi = Math.round(v[2] - minZ); // NEW
                if (xi < 0 || xi >= gridW || zi < 0 || zi >= gridH) continue; // NEW
                int idx = zi * gridW + xi; // NEW
                if (vni >= 0 && vni < normals.size()) { // NEW
                    float[] n = normals.get(vni); // NEW
                    normAccum[idx][0] += n[0]; // NEW
                    normAccum[idx][1] += n[1]; // NEW
                    normAccum[idx][2] += n[2]; // NEW
                    normCount[idx]++; // NEW
                } // NEW
            } // NEW
        } // NEW

        // NEW: finalise normal grid — average and normalise each cell
        for (int i = 0; i < gridW * gridH; i++) { // NEW
            if (normCount[i] > 0) { // NEW
                float nx = normAccum[i][0] / normCount[i]; // NEW
                float ny = normAccum[i][1] / normCount[i]; // NEW
                float nz = normAccum[i][2] / normCount[i]; // NEW
                float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz); // NEW
                if (len > 0) { nx /= len; ny /= len; nz /= len; } // NEW
                normalGrid[i] = new float[]{nx, ny, nz}; // NEW
            } else { // NEW
                normalGrid[i] = new float[]{0, 1, 0}; // NEW: default flat normal
            } // NEW
        } // NEW
    } // NEW

    // NEW: bilinearly interpolate terrain height at any world (x, z) position
    public float getHeightAt(float wx, float wz) { // NEW
        float gx = wx - minX; // NEW: convert world to grid coords
        float gz = wz - minZ; // NEW

        int x0 = (int) Math.floor(gx); // NEW
        int z0 = (int) Math.floor(gz); // NEW
        int x1 = x0 + 1; // NEW
        int z1 = z0 + 1; // NEW

        // NEW: clamp to grid bounds
        x0 = Math.max(0, Math.min(gridW - 1, x0)); // NEW
        z0 = Math.max(0, Math.min(gridH - 1, z0)); // NEW
        x1 = Math.max(0, Math.min(gridW - 1, x1)); // NEW
        z1 = Math.max(0, Math.min(gridH - 1, z1)); // NEW

        float tx = gx - (int) Math.floor(gx); // NEW: fractional part for lerp
        float tz = gz - (int) Math.floor(gz); // NEW

        // NEW: bilinear interpolation across the four surrounding grid corners
        float h00 = heightGrid[z0 * gridW + x0]; // NEW
        float h10 = heightGrid[z0 * gridW + x1]; // NEW
        float h01 = heightGrid[z1 * gridW + x0]; // NEW
        float h11 = heightGrid[z1 * gridW + x1]; // NEW

        float h0 = h00 + tx * (h10 - h00); // NEW: lerp along x at z0
        float h1 = h01 + tx * (h11 - h01); // NEW: lerp along x at z1
        return h0 + tz * (h1 - h0); // NEW: lerp along z
    } // NEW

    // NEW: return interpolated surface normal at world (x, z) — used for bounce reflection
    public float[] getNormalAt(float wx, float wz) { // NEW
        float gx = wx - minX; // NEW
        float gz = wz - minZ; // NEW

        int x0 = Math.max(0, Math.min(gridW - 1, (int) Math.floor(gx))); // NEW
        int z0 = Math.max(0, Math.min(gridH - 1, (int) Math.floor(gz))); // NEW
        int x1 = Math.max(0, Math.min(gridW - 1, x0 + 1)); // NEW
        int z1 = Math.max(0, Math.min(gridH - 1, z0 + 1)); // NEW

        float tx = gx - (int) Math.floor(gx); // NEW
        float tz = gz - (int) Math.floor(gz); // NEW

        float[] n00 = normalGrid[z0 * gridW + x0]; // NEW
        float[] n10 = normalGrid[z0 * gridW + x1]; // NEW
        float[] n01 = normalGrid[z1 * gridW + x0]; // NEW
        float[] n11 = normalGrid[z1 * gridW + x1]; // NEW

        // NEW: bilinearly interpolate normal components
        float nx = n00[0]*(1-tx)*(1-tz) + n10[0]*tx*(1-tz) + n01[0]*(1-tx)*tz + n11[0]*tx*tz; // NEW
        float ny = n00[1]*(1-tx)*(1-tz) + n10[1]*tx*(1-tz) + n01[1]*(1-tx)*tz + n11[1]*tx*tz; // NEW
        float nz = n00[2]*(1-tx)*(1-tz) + n10[2]*tx*(1-tz) + n01[2]*(1-tx)*tz + n11[2]*tx*tz; // NEW

        // NEW: re-normalise after interpolation
        float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz); // NEW
        if (len > 0) { nx /= len; ny /= len; nz /= len; } // NEW
        else { ny = 1; } // NEW: fallback to flat up

        return new float[]{nx, ny, nz}; // NEW
    } // NEW

    private void parseBounds(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("v ")) {
                    String[] t = line.split("\\s+");
                    float x = Float.parseFloat(t[1]);
                    float z = Float.parseFloat(t[3]);
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    public void render() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        mesh.render();
    }

    public float getCenterX()      { return (minX + maxX) / 2.0f; }
    public float getCenterY()      { return (minZ + maxZ) / 2.0f; }
    public float getMaxDimension() { return Math.max(maxX - minX, maxZ - minZ); }
    public float getMinX() { return minX; }
    public float getMaxX() { return maxX; }
    public float getMinZ() { return minZ; }
    public float getMaxZ() { return maxZ; }
}