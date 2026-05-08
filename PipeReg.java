public class PipeReg {
    enum Type {
        empty, instruction, stall, squash
    }

    Type type;
    Instruction instr;

    PipeReg(Type type, Instruction instr) {
        this.type = type;
        this.instr = instr;
    }

    static PipeReg empty() {
        return new PipeReg(Type.empty, null);
    }

    static PipeReg instruction(Instruction instr) {
        return new PipeReg(Type.instruction, instr);
    }

    static PipeReg stall() {
        return new PipeReg(Type.stall, null);
    }

    static PipeReg squash() {
        return new PipeReg(Type.squash, null);
    }

    public Instruction getInstruction() {
        return this.instr;
    }

    public String toString() {
        switch (this.type) {
            case empty:
                return "empty";
            case instruction:
                return this.instr.getName();
            case stall:
                return "stall";
            case squash:
                return "squash";
            default:
                return "error";
        }
    }
}