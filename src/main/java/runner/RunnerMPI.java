package runner;

import api.APIopen;
import mpi.MPI;
import otm.OTMRunner;
import metagraph.MyMetaGraph;
import translator.Translator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;

public class RunnerMPI {

    private static final boolean run_mpi = true;

    public static String prefix;
    public static float sim_dt;
    public static float duration;
    public static boolean writeoutput;

    public static double metagraph_load_time;
    public static double create_subscenario_time;
    public static double create_translator_time;
    public static double mpi_run_time;
    public static double comm_time;

    /**
     * 0 string : [prefix]
     * 1 float : [sim_dt] sim dt in seconds
     * 2 float : [duration] sim duration in seconds
     * 3 boolean : [writeouput] true->write network state to files
     */
    public static void main(String[] args) throws Exception {

        prefix = args[0];
        sim_dt = Float.parseFloat(args[1]);
        duration = Float.parseFloat(args[2]);
        writeoutput = Boolean.valueOf(args[3]);

        File prefix_file = new File(prefix);
        String prefix_name = prefix_file.getName();
        String output_folder = prefix_file.getParentFile().getAbsolutePath();

        double start;
        float out_dt = sim_dt;

        // initialize mpi
        if(run_mpi)
            MPI.Init(args);

        int my_rank = run_mpi ? MPI.COMM_WORLD.getRank() : 0;

        // read my metagraph
        start = MPI.wtime();
        MyMetaGraph my_metagraph = new MyMetaGraph(String.format("%s_mg_%d.json",prefix,my_rank));
        metagraph_load_time = MPI.wtime()-start;

        // extract the subscenario for this rank
        start = MPI.wtime();
        APIopen api = new APIopen(OTM.load(String.format("%s_cfg_%d.xml",prefix,my_rank),sim_dt,true,"ctm"));
        OTM.initialize(api.scenario(), new RunParameters(null, null, null, 0f, duration));
        if(writeoutput)
            api.api.request_links_veh(String.format("%s_%d",prefix_name,my_rank),output_folder, null, api.api.get_link_ids(), out_dt);
        create_subscenario_time = MPI.wtime()-start;

        // create communicator and translator
        start = MPI.wtime();
        int [] neighbors = my_metagraph.get_neighbors();
        mpi.GraphComm comm = run_mpi ?
                MPI.COMM_WORLD.createDistGraphAdjacent(neighbors, neighbors, MPI.INFO_NULL, false) :
                null;

        Translator translator = new Translator(my_metagraph,api.scenario());
        create_translator_time = MPI.wtime()-start;

        start = MPI.wtime();
        RunParameters runParam = new RunParameters(null, null, null, 0f,duration);
        comm_time = OTMRunner.run(api.scenario(), runParam,translator,comm);
        mpi_run_time = MPI.wtime()-start;

        // write timers
        String filename = String.format("%s_%d_timers.txt",prefix,my_rank);
        write_output(filename,translator);

        // finalize mpi
        MPI.Finalize();
    }

    private static void write_output(String filename, Translator translator) {
        File file = new File(filename);
        FileWriter fr = null;
        try{
            fr = new FileWriter(file);
            fr.write(String.format("metagraph_load_time\t%f\n",metagraph_load_time));
            fr.write(String.format("create_subscenario_time\t%f\n",create_subscenario_time));
            fr.write(String.format("create_translator_time\t%f\n",create_translator_time));
            fr.write(String.format("mpi_run_time\t%f\n",mpi_run_time));
            fr.write(String.format("comm_time\t%f\n",comm_time));
            fr.write(String.format("num_double_send\t%d\n",translator.encoder.items.size()));
            fr.write(String.format("num_double_receive\t%d\n",translator.decoder.items.size()));
        } catch (IOException e) {
            e.printStackTrace();
        }finally{
            try {
                fr.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
