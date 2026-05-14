// Names: Jonah Chan, Nicholas Chapman
// CPE 315-2264
// Lab 4

import java.io.File; // Import the File class
import java.io.FileNotFoundException; // Import this class to handle errors
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Scanner; // Import the Scanner class to read text files

public class lab4 {

    static HashMap<String, Integer> labelAddr = new HashMap<>(); // hash map to store labels + their addr
    static ArrayList<Instruction> instructionArray = new ArrayList<>();
    static int[] registers = new int[32]; // N.B. remember to always set $0 and $zero to 0!!
    static int[] dataMem = new int[8192];

    static int PC = 0;
    static int branchPC = -1;
    static int cycles = 0;
    static int instructionsExecuted = 0;
    static int stallCount = 0;
    static int squashCycles = 0;
    static int branchDelay = 0;

    static PipeReg if_id = PipeReg.empty();
    static PipeReg id_exe = PipeReg.empty();
    static PipeReg exe_mem = PipeReg.empty();
    static PipeReg mem_wb = PipeReg.empty();

    static boolean pipelineFinished() {
        return if_id.isEmpty() && id_exe.isEmpty() && exe_mem.isEmpty() && mem_wb.isEmpty();
    }

    static void printPipeline() {
        System.out.printf("\n%-8s%-8s%-8s%-8s%s%n", "pc", "if/id", "id/exe", "exe/mem", "mem/wb");
        System.out.printf("%-8d%-8s%-8s%-8s%s%n\n", PC, if_id, id_exe, exe_mem, mem_wb);
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Invalid number of arguments provided.");
            System.exit(1);
        }
        // open file specified in first command line arg
        parseFile(new File(args[0]));

        if (args.length > 1) {
            scriptMode(args[1]);
        } else {
            interactiveMode();
        }
    }

    public static void parseFile(File input) {
        // try with resources for auto-cleanup
        try (Scanner inputReader = new Scanner(input)) {
            int instructionIdx = 0; // initialize instruction counter

            // FIRST PASS - COMPUTE ADDRESSES OF LABELS
            while (inputReader.hasNextLine()) {
                String line = inputReader.nextLine();

                // ignore comments
                if (line.indexOf("#") != -1) {
                    line = line.substring(0, line.indexOf("#"));
                }

                // label handling
                if (line.indexOf(":") != -1) {
                    // label is all non-whitspace up until colon
                    String label = line.substring(0, line.indexOf(":"));
                    label = label.trim();

                    // associate the label with the following instruction
                    labelAddr.put(label, instructionIdx);

                    // remove the label from the line so that we can parse the rest of it
                    line = line.substring(line.indexOf(":") + 1);
                }

                // remove whitespace
                line = line.trim();
                if (line.isEmpty())
                    continue;

                // we now have removed labels and comments from the line
                instructionIdx += 1; // increment instruction counter
                String regex = "[,\\.\\s()$]+";
                String[] parsedLine = line.split(regex);
                String name = parsedLine[0];
                switch (name) {
                    // r-types with rs, rt, rd, shamt = 0
                    case "add":
                    case "sub":
                    case "and":
                    case "or":
                    case "slt":
                        instructionArray.add(
                                new Rtype(
                                        name,
                                        Instruction.getOpcode(name),
                                        Register.getNumber(parsedLine[2]),
                                        Register.getNumber(parsedLine[3]),
                                        Register.getNumber(parsedLine[1]),
                                        0));
                        break;
                    // r-type with shamt != 0
                    case "sll":
                        instructionArray.add(
                                new Rtype(
                                        name,
                                        Instruction.getOpcode(name),
                                        0,
                                        Register.getNumber(parsedLine[2]),
                                        Register.getNumber(parsedLine[1]),
                                        Integer.parseInt(parsedLine[3])));
                        break;
                    // jump to addr stored in register
                    case "jr":
                        instructionArray.add(
                                new Rtype(
                                        name,
                                        Instruction.getOpcode(name),
                                        Register.getNumber(parsedLine[1]),
                                        0,
                                        0,
                                        0));
                        break;
                    // i-types with 3 "arguments"
                    case "addi":
                        instructionArray.add(
                                new Itype(
                                        name,
                                        Instruction.getOpcode(name),
                                        Register.getNumber(parsedLine[2]),
                                        Register.getNumber(parsedLine[1]),
                                        Integer.parseInt(parsedLine[3])));
                        break;
                    case "beq":
                    case "bne":
                        instructionArray.add(
                                new Itype(
                                        name,
                                        Instruction.getOpcode(name),
                                        Register.getNumber(parsedLine[1]),
                                        Register.getNumber(parsedLine[2]),
                                        parsedLine[3]));
                        break;
                    // i-type of form rt, imm(rs)
                    case "lw":
                    case "sw":
                        instructionArray.add(
                                new Itype(
                                        name,
                                        Instruction.getOpcode(name),
                                        Register.getNumber(parsedLine[3]),
                                        Register.getNumber(parsedLine[1]),
                                        Integer.parseInt(parsedLine[2])));
                        break;
                    // jump to label
                    case "j":
                    case "jal":
                        instructionArray.add(
                                new Jtype(
                                        name,
                                        Instruction.getOpcode(name),
                                        parsedLine[1]));
                        break;
                    default:
                        instructionArray.add(new Jtype(name, "invalid", -1) {
                        });
                        break;
                }
            }
        } catch (FileNotFoundException e) {
            System.out.println("Error opening specified file");
            e.printStackTrace();
            System.exit(1);
        }

        // SECOND PASS - REPLACE LABELS WITH ADDRESSES
        for (int i = 0; i < instructionArray.size(); i++) {
            Instruction inst = instructionArray.get(i);
            if (inst.getOpcode().equals("invalid")) {
                System.out.println("invalid instruction: " + inst.getName());
                System.exit(1);
            }
            // beq and bne: 16-bit immediate = labelAddr - (curr instruction idx + 1)
            if (inst.getName().equals("beq") || inst.getName().equals("bne")) {
                ((Itype) inst).setImmediate(
                        labelAddr.get(((Itype) inst).getLabel()) - (i + 1));
                // j and jal: use the absolute address of the label
            } else if (inst.getName().equals("j") || inst.getName().equals("jal")) {
                ((Jtype) inst).setAddress(
                        labelAddr.get(((Jtype) inst).getLabel()));
            }
        }
    }

    public static void interactiveMode() {
        Scanner usrInput = new Scanner(System.in);
        while (true) {
            System.out.print("mips> ");
            String input = usrInput.nextLine();
            if (input.charAt(0) == 'q')
                break;
            executeCmd(input);
        }

        usrInput.close();
    }

    public static void scriptMode(String file) {
        File myObj = new File(file);

        // try-with-resources: Scanner will be closed automatically
        try (Scanner scanner = new Scanner(myObj)) {
            while (scanner.hasNextLine()) {
                String cmd = scanner.nextLine();
                System.out.println("mips> " + cmd);
                if (cmd.equals("q"))
                    System.exit(0);
                executeCmd(cmd);
            }
        } catch (FileNotFoundException e) {
            System.out.println("Unable to open script file.");
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void executeCmd(String cmd) {
        switch (cmd.charAt(0)) {
            case 'h': // h = show help
                System.out.println("h = show help");
                System.out.println("d = dump register state");
                System.out.println("p = show pipeline registers");
                System.out.println(
                        "s = step through a single clock cycle step (i.e. simulate 1 cycle and stop)");
                System.out.println("s num = step through num clock cycles");
                System.out.println(
                        "r = run until the program ends and display timing summary");
                System.out.println(
                        "m num1 num2 = display data memory from location num1 to num2");
                System.out.println(
                        "c = clear all registers, memory, and the program counter to 0");
                System.out.println("q = exit the program\n");
                break;
            case 'd': // dump register state
                System.out.println("\npc = " + PC);
                System.out.printf(
                        "%-16s%-16s%-16s%s%n",
                        "$0 = " + registers[0],
                        "$v0 = " + registers[2],
                        "$v1 = " + registers[3],
                        "$a0 = " + registers[4]);
                System.out.printf(
                        "%-16s%-16s%-16s%s%n",
                        "$a1 = " + registers[5],
                        "$a2 = " + registers[6],
                        "$a3 = " + registers[7],
                        "$t0 = " + registers[8]);
                System.out.printf(
                        "%-16s%-16s%-16s%s%n",
                        "$t1 = " + registers[9],
                        "$t2 = " + registers[10],
                        "$t3 = " + registers[11],
                        "$t4 = " + registers[12]);
                System.out.printf(
                        "%-16s%-16s%-16s%s%n",
                        "$t5 = " + registers[13],
                        "$t6 = " + registers[14],
                        "$t7 = " + registers[15],
                        "$s0 = " + registers[16]);
                System.out.printf(
                        "%-16s%-16s%-16s%s%n",
                        "$s1 = " + registers[17],
                        "$s2 = " + registers[18],
                        "$s3 = " + registers[19],
                        "$s4 = " + registers[20]);
                System.out.printf(
                        "%-16s%-16s%-16s%s%n",
                        "$s5 = " + registers[21],
                        "$s6 = " + registers[22],
                        "$s7 = " + registers[23],
                        "$t8 = " + registers[24]);
                System.out.printf(
                        "%-16s%-16s%s%n%n",
                        "$t9 = " + registers[25],
                        "$sp = " + registers[29],
                        "$ra = " + registers[31]);

                break;
            case 'p': // show pipeline registers
                printPipeline();
                break;
            case 's':
                // determine how many clock cycles to execute
                int n = cmd.length() > 1
                        ? Integer.parseInt(
                                cmd.substring(cmd.indexOf('s') + 1).trim())
                        : 1;
                // step through n number of clock cycles
                for (int i = 0; i < n; i++) {
                    if (PC >= instructionArray.size() && pipelineFinished() && squashCycles == 0) {
                        break;
                    }
                    executeCycle();
                }
                printPipeline();
                break;
            case 'r': // run until the program ends
                while (PC < instructionArray.size() || !pipelineFinished() || squashCycles > 0) {
                    executeCycle();
                }
                System.out.println("\nProgram complete");
                System.out.printf("CPI = %.3f\tCycles = %d\tInstructions = %d%n\n",
                        (double) cycles / instructionsExecuted,
                        cycles,
                        instructionsExecuted);

                break;
            case 'm': // m n1 n2 display dataMem from n1 to n2
                String regex = "[,\\.\\s()$]+";
                String[] parsedLine = cmd.split(regex);
                int n1 = Integer.parseInt(parsedLine[1]);
                int n2 = Integer.parseInt(parsedLine[2]);
                if (n1 < 0 ||
                        n1 > dataMem.length ||
                        n2 < 0 ||
                        n2 > dataMem.length ||
                        n1 > n2) {
                    System.out.println("Invalid memory range.");
                    break;
                }
                for (int i = n1; i <= n2; i++) {
                    System.out.println("[" + i + "] = " + dataMem[i]);
                }
                break;
            case 'c': // clear all registers, mem, reset PC
                Arrays.fill(registers, 0);
                Arrays.fill(dataMem, 0);
                PC = 0;
                cycles = 0;
                instructionsExecuted = 0;
                if_id = PipeReg.empty();
                id_exe = PipeReg.empty();
                exe_mem = PipeReg.empty();
                mem_wb = PipeReg.empty();
                squashCycles = 0;
                branchPC = -1;
                branchDelay = 0;
                System.out.println("        Simulator reset\n");
                break;
            default:
                System.out.println("Error: Invalid command. 'h' for help menu");
        }
    }

    static boolean loadAfterUse(Instruction if_id, Instruction currInst) {
        if (if_id == null) {
            return false;
        }
        if (!if_id.getName().equals("lw")) {
            return false;
        }
        int previouslyUsedReg = ((Itype) if_id).getRt();
        if (previouslyUsedReg == 0) {
            return false; // can't write to zero register
        }
        if (currInst instanceof Rtype) {
            Rtype r = (Rtype) currInst;

            if (r.getName().equals("sll") && r.getRt() == previouslyUsedReg) {
                return true;
            }
            if (r.getName().equals("jr") && r.getRs() == previouslyUsedReg) {
                return true;
            }
            return r.getRs() == previouslyUsedReg || r.getRt() == previouslyUsedReg;
        }

        if (currInst instanceof Itype) {
            Itype i = (Itype) currInst;
            String name = currInst.getName();

            if (name.equals("sw") || name.equals("beq") || name.equals("bne")) {
                return i.getRs() == previouslyUsedReg || i.getRt() == previouslyUsedReg;
            }

            return i.getRs() == previouslyUsedReg;
        }
        return false;
    }

    static void executeCycle() {
        // SPECIAL CONDITIONS
        // if we took the branch, that finished during the fetch cycle in our simulator
        // however, we want to fetch the next three instructions before squashing them
        // so we'll fetch them normally but return before we can execute them
        // that gets us the three next instructions in the pipeline without affecting
        // our register results
        if (branchDelay > 0) {
            cycles++;
            branchDelay--;
            mem_wb = exe_mem;
            exe_mem = id_exe;
            id_exe = if_id;
            if_id = PipeReg.instruction(instructionArray.get(PC));
            PC++;
            return;
        }

        // squashCycles is now only used by jumps
        if (squashCycles > 0) {
            cycles++;
            squashCycles--;
            if (branchPC != -1) {
                PC = branchPC;
                branchPC = -1;
            }
            mem_wb = exe_mem;
            exe_mem = id_exe;
            id_exe = if_id;
            if_id = PipeReg.squash();
            return;

            // this case is only for branches
            // we squash the three instructions
        } else if (branchPC != -1) {
            cycles++;
            PC = branchPC;
            branchPC = -1;
            mem_wb = exe_mem;
            exe_mem = PipeReg.squash();
            id_exe = PipeReg.squash();
            if_id = PipeReg.squash();
            return;

            // stalls for load-after-use conditions
        } else if (stallCount > 0) {
            cycles++;
            stallCount--;
            mem_wb = exe_mem;
            exe_mem = id_exe;
            id_exe = PipeReg.stall();
            return;
        }

        // if we've finished instructions
        // run until the pipeline is completely empty
        // AKA finish all in-progress instructions
        if (PC >= instructionArray.size()) {
            cycles++;
            mem_wb = exe_mem;
            exe_mem = id_exe;
            id_exe = if_id;
            if_id = PipeReg.empty();
            return;
        }

        // NORMAL EXECUTION
        cycles++;
        Instruction currInst = instructionArray.get(PC);

        if (loadAfterUse(if_id.getInstruction(), currInst)) {
            stallCount = 1;
        }

        int oldPC = PC;
        executeInstruction(currInst);
        instructionsExecuted++;

        if (PC != oldPC + 1 || branchPC != -1) { // if we jumped to some kind of target...
            if (currInst.getName().equals("beq") || currInst.getName().equals("bne")) {
                branchDelay = 2;
            }
        }
        if (currInst.getName().equals("j") || currInst.getName().equals("jr")
                || currInst.getName().equals("jal")) { // jumps have unconditional squash
            squashCycles = 1;
        }
        // Scoot the pipeline registers over
        mem_wb = exe_mem;
        exe_mem = id_exe;
        id_exe = if_id;
        if_id = PipeReg.instruction(currInst);
    }

    static void executeInstruction(Instruction currInst) {

        switch (currInst.getName()) {
            case "add": {
                int rd = ((Rtype) currInst).getRd();
                registers[rd] = registers[((Rtype) currInst).getRs()] +
                        registers[((Rtype) currInst).getRt()];
                PC++;
                break;
            }
            case "sub": {
                int rd = ((Rtype) currInst).getRd();
                registers[rd] = registers[((Rtype) currInst).getRs()] -
                        registers[((Rtype) currInst).getRt()];
                PC++;
                break;
            }
            case "and": {
                int rd = ((Rtype) currInst).getRd();
                registers[rd] = registers[((Rtype) currInst).getRs()] &
                        registers[((Rtype) currInst).getRt()];
                PC++;
                break;
            }
            case "or": {
                int rd = ((Rtype) currInst).getRd();
                registers[rd] = registers[((Rtype) currInst).getRs()] |
                        registers[((Rtype) currInst).getRt()];
                PC++;
                break;
            }
            case "slt": {
                int rd = ((Rtype) currInst).getRd();
                registers[rd] = registers[((Rtype) currInst).getRs()] < registers[((Rtype) currInst).getRt()]
                        ? 1
                        : 0;
                PC++;
                break;
            }
            case "sll": {
                int rd = ((Rtype) currInst).getRd();
                registers[rd] = registers[((Rtype) currInst).getRt()] << ((Rtype) currInst).getShamt();
                PC++;
                break;
            }
            case "jr": {
                branchPC = registers[((Rtype) currInst).getRs()];
                PC++;
                break;
            }
            case "addi": {
                int rt = ((Itype) currInst).getRt();
                registers[rt] = registers[((Itype) currInst).getRs()] +
                        ((Itype) currInst).getImmediate();
                PC++;
                break;
            }
            case "beq": {
                branchPC = registers[((Itype) currInst).getRs()] == registers[((Itype) currInst).getRt()]
                        ? PC + 1 + ((Itype) currInst).getImmediate()
                        : -1;
                PC++;
                break;
            }
            case "bne": {
                branchPC = registers[((Itype) currInst).getRs()] != registers[((Itype) currInst).getRt()]
                        ? PC + 1 + ((Itype) currInst).getImmediate()
                        : -1;
                PC++;
                break;
            }
            case "lw": {
                // reg(rt) = mem(rs + imm)
                registers[((Itype) currInst).getRt()] = dataMem[registers[(((Itype) currInst).getRs())] +
                        ((Itype) currInst).getImmediate()];
                PC++;
                break;
            }
            case "sw": {
                // mem(rs + imm) = reg(rt)
                dataMem[registers[(((Itype) currInst).getRs())] +
                        ((Itype) currInst).getImmediate()] = registers[((Itype) currInst).getRt()];
                PC++;
                break;
            }
            case "j": {
                int dest = ((Jtype) currInst).getAddress();
                if (dest >= instructionArray.size()) {
                    System.out.println(
                            "Error: j instruction tried to access an invalid instruction index.");
                    System.exit(1);
                }
                branchPC = dest;
                PC++;
                break;
            }
            case "jal": {
                registers[Register.getNumber("ra")] = PC + 1;
                int dest = ((Jtype) currInst).getAddress();
                if (dest >= instructionArray.size()) {
                    System.out.println(
                            "Error: jal instruction tried to access an invalid instruction index.");
                    System.exit(1);
                }
                branchPC = dest;
                PC++;
                break;
            }
            default: {
                System.out.println(
                        "Error: unsupported instruction " + currInst.getName());
                System.exit(1);
            }
        }
    }
}
