//javac -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainViewer.java
//java -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainViewer

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public class TerrainViewer {

    // --- OBJ data ---
    static float[] vx, vy, vz;
    static float[] nx, ny, nz;
    static int[]   faceVI, faceNI; // vertex/normal index per face-corner
    static int     nVerts, nNorms, nFaceCorners;

    // --- Terrain bounds ---
    static float minY, maxY, cx, cz, size;

    // --- GL handles ---
    static int vaoId, vboId, iboId;

    public static void main(String[] args) throws Exception {
        loadObj("fractal_terrain.obj");
        render();
    }

    // ---------------------------------------------------------------
    // OBJ loader
    // ---------------------------------------------------------------
    static void loadObj(String path) throws IOException {
        List<Float> lVX = new ArrayList<>(), lVY = new ArrayList<>(), lVZ = new ArrayList<>();
        List<Float> lNX = new ArrayList<>(), lNY = new ArrayList<>(), lNZ = new ArrayList<>();
        List<Integer> lFVI = new ArrayList<>(), lFNI = new ArrayList<>();

        float minX=1e9f, maxX=-1e9f, minZ=1e9f, maxZ=-1e9f;
        minY=1e9f; maxY=-1e9f;

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.split("\\s+");
                    float x=Float.parseFloat(p[1]), y=Float.parseFloat(p[2]), z=Float.parseFloat(p[3]);
                    lVX.add(x); lVY.add(y); lVZ.add(z);
                    if (x<minX) minX=x; if (x>maxX) maxX=x;
                    if (y<minY) minY=y; if (y>maxY) maxY=y;
                    if (z<minZ) minZ=z; if (z>maxZ) maxZ=z;
                } else if (line.startsWith("vn ")) {
                    String[] p = line.split("\\s+");
                    lNX.add(Float.parseFloat(p[1]));
                    lNY.add(Float.parseFloat(p[2]));
                    lNZ.add(Float.parseFloat(p[3]));
                } else if (line.startsWith("f ")) {
                    String[] p = line.split("\\s+");
                    for (int i = 1; i <= 3; i++) {
                        String[] idx = p[i].split("//");
                        lFVI.add(Integer.parseInt(idx[0]) - 1);
                        lFNI.add(Integer.parseInt(idx[1]) - 1);
                    }
                }
            }
        }

        nVerts = lVX.size(); nNorms = lNX.size(); nFaceCorners = lFVI.size();
        vx = toArray(lVX); vy = toArray(lVY); vz = toArray(lVZ);
        nx = toArray(lNX); ny = toArray(lNY); nz = toArray(lNZ);
        faceVI = toIntArray(lFVI); faceNI = toIntArray(lFNI);

        cx = (minX + maxX) / 2f;
        cz = (minZ + maxZ) / 2f;
        size = Math.max(maxX - minX, maxZ - minZ);
    }

    // ---------------------------------------------------------------
    // LWJGL render loop
    // ---------------------------------------------------------------
    static void render() {
        if (!GLFW.glfwInit()) throw new RuntimeException("GLFW init failed");

        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);

        long win = GLFW.glfwCreateWindow(900, 700, "Fractal Terrain - Isometric", 0, 0);
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_LIGHT0);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);

        FloatBuffer lightPos = BufferUtils.createFloatBuffer(4).put(new float[]{1,2,1,0}); lightPos.flip();
        FloatBuffer ambient  = BufferUtils.createFloatBuffer(4).put(new float[]{0.3f,0.3f,0.3f,1}); ambient.flip();
        FloatBuffer diffuse  = BufferUtils.createFloatBuffer(4).put(new float[]{0.9f,0.9f,0.8f,1}); diffuse.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPos);
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_AMBIENT,  ambient);
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_DIFFUSE,  diffuse);

        while (!GLFW.glfwWindowShouldClose(win)) {
            int[] w = {0}, h = {0};
            GLFW.glfwGetFramebufferSize(win, w, h);
            GL11.glViewport(0, 0, w[0], h[0]);

            GL11.glClearColor(0.12f, 0.13f, 0.17f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            setupIsometric(w[0], h[0]);
            drawTerrain();

            GLFW.glfwSwapBuffers(win);
            GLFW.glfwPollEvents();
            if (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS)
                GLFW.glfwSetWindowShouldClose(win, true);
        }
        GLFW.glfwTerminate();
    }

    // ---------------------------------------------------------------
    // Isometric projection  (ortho + 45° Y + 35.26° X)
    // ---------------------------------------------------------------
    static void setupIsometric(int w, int h) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        float aspect = (float) w / h;
        float s = size * 0.55f;
        GL11.glOrtho(-s*aspect, s*aspect, -s, s, -size*10, size*10);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glRotatef( 35.264f, 1, 0, 0);
        GL11.glRotatef(-45.0f,   0, 1, 0);
        float midY = (minY + maxY) / 2f;
        GL11.glTranslatef(-cx, -midY, -cz);
    }

    // ---------------------------------------------------------------
    // Draw all triangles with height-based color
    // ---------------------------------------------------------------
    static void drawTerrain() {
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int i = 0; i < nFaceCorners; i++) {
            int vi = faceVI[i], ni = faceNI[i];
            GL11.glNormal3f(nx[ni], ny[ni], nz[ni]);
            heightColor(vy[vi]);
            GL11.glVertex3f(vx[vi], vy[vi], vz[vi]);
        }
        GL11.glEnd();
    }

    // Low=blue-green, mid=green, high=white
    static void heightColor(float y) {
        float t = (y - minY) / (maxY - minY + 1e-6f);
        float r, g, b;
        if (t < 0.3f) {
            float u = t / 0.3f;
            r = 0.1f*u; g = 0.4f+0.2f*u; b = 0.6f-0.2f*u;
        } else if (t < 0.7f) {
            float u = (t-0.3f)/0.4f;
            r = 0.1f+0.5f*u; g = 0.6f-0.1f*u; b = 0.4f-0.3f*u;
        } else {
            float u = (t-0.7f)/0.3f;
            r = 0.6f+0.4f*u; g = 0.5f+0.5f*u; b = 0.1f+0.9f*u;
        }
        GL11.glColor3f(r, g, b);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------
    static float[] toArray(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i=0;i<a.length;i++) a[i]=l.get(i);
        return a;
    }
    static int[] toIntArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i=0;i<a.length;i++) a[i]=l.get(i);
        return a;
    }
}